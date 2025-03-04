package studio.midoridesign.compassx;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.DeviceOrientation;
import com.google.android.gms.location.DeviceOrientationListener;
import com.google.android.gms.location.DeviceOrientationRequest;
import com.google.android.gms.location.FusedOrientationProviderClient;
import com.google.android.gms.location.LocationServices;

import android.app.Activity;
import android.content.Context;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.EventChannel;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;

import java.util.HashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

final class HelperFunctions {
    static final String logName = "CompassX";
    static void passHeading(EventChannel.EventSink events, HeadingProvider headingProvider) {
        events.success(new HashMap<String, Object>() {
            {
                put("heading", headingProvider.getHeading());
                put("accuracy", headingProvider.getAccuracy());
                put("shouldCalibrate", headingProvider.shouldCalibrate());
            }
        });
    }
}
interface HeadingListener {
    void onHeading(HeadingProvider headingProvider);
}

interface HeadingProvider {
    float getHeading();
    float getAccuracy();
    boolean shouldCalibrate();
    void register(HeadingListener listener);
    void dispose();
}

abstract class BaseHeading implements HeadingProvider {
    float heading;
    float accuracy;
    boolean needsCalibration=false;

    @Override
    public float getHeading() { return heading; }
    @Override
    public float getAccuracy() { return accuracy; }
    @Override
    public boolean shouldCalibrate() { return needsCalibration; }
    HeadingListener headingListener;
    @Override
    public void register(HeadingListener listener) {
        this.headingListener = listener;
    }
}

/**
 * Provides the magnetic heading based on Rotation Vector and an external
 * magnetic declination. If declination = 0, then true heading == magnetic heading.
**/
class RotationSensorHeading extends BaseHeading implements SensorEventListener, EventChannel.StreamHandler {
    SensorManager sensorManager;
    Sensor rotationSensor;
    EventChannel.EventSink events;
    float declination = 0;
    float[] rotationMatrix = new float[9];
    float[] orientationAngles = new float[3];

    // The location manager needs to be known in order to
    // calculate the declination automatically and not externally, if needed
    // Pass null otherwise
    LocationManager locationManager;
    LocationListener locationListener;
    RotationSensorHeading(
            SensorManager sensorManager,
            Sensor rotationSensor,
            LocationManager locationManager)
    {
        this.sensorManager = sensorManager;
        this.rotationSensor = rotationSensor;
        this.locationManager = locationManager;
        // A random init value to start the stream
        heading = -1000;

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                declination = new GeomagneticField(
                        (float) location.getLatitude(),
                        (float) location.getLongitude(),
                        (float) location.getAltitude(),
                        System.currentTimeMillis()
                ).getDeclination();
            }
        };
    }
    @Override
    public void dispose() {
        this.sensorManager.unregisterListener(this);
        this.events = null;

        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR){
            return;
        }

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        SensorManager.getOrientation(rotationMatrix, orientationAngles);

        float azimuth = (float) Math.toDegrees(orientationAngles[0]);
        float newHeading = (azimuth + declination + 360) % 360;
        float accuracyRadian = event.values[4];
        float newAcc =
                accuracyRadian != -1 ? (float) Math.toDegrees(accuracyRadian) : -1;

        // Guard for small values
        if (Math.abs(heading - newHeading) < 0.1) return;

        heading = newHeading;
        accuracy = newAcc;
        if (headingListener != null) {
            headingListener.onHeading(RotationSensorHeading.this);
        }

        if (events != null) {
            HelperFunctions.passHeading(events, this);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (rotationSensor != sensor) return;
        needsCalibration = accuracy != SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        if (rotationSensor == null) {
            events.error("NO_ROTATION_SENSOR", "Rotation sensor not found", null);
            events.endOfStream();
            this.events = null;
            return;
        }

        // Arbitrarily large minTime to avoid battery usage
        if (locationManager != null) {
            String provider = locationManager.hasProvider(LocationManager.FUSED_PROVIDER) ? LocationManager.FUSED_PROVIDER : LocationManager.GPS_PROVIDER;
            locationManager.requestLocationUpdates(provider, 300000L, 10f, this.locationListener);
        }

        this.events = events;
        this.sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public void onCancel(Object arguments) {
        dispose();
    }
}

class HeadingSensorHeading extends BaseHeading implements SensorEventListener, EventChannel.StreamHandler {
    SensorManager sensorManager;
    Sensor headingSensor;
    EventChannel.EventSink events;
    HeadingSensorHeading(
            SensorManager sensorManager,
            Sensor headingSensor) {
        this.sensorManager = sensorManager;
        this.headingSensor = headingSensor;
    }

    @Override
    public void dispose() {
        this.sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float heading = event.values[0];
        float accuracy = event.values[1];

        if (Math.abs(this.heading - heading) < 0.1){
            return;
        }

        this.heading = heading;
        this.accuracy = accuracy;

        if (headingListener != null) {
            headingListener.onHeading(this);
        }

        if (events != null) {
            HelperFunctions.passHeading(events, this);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (headingSensor != sensor) return;
        needsCalibration = accuracy != SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        if (headingSensor == null) {
            events.error("NO_HEADING_SENSOR", "Heading sensor not found", null);
            events.endOfStream();
            this.events = null;
            return;
        }
        this.events = events;
        this.sensorManager.registerListener(this, headingSensor, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public void onCancel(Object arguments) {
        this.sensorManager.unregisterListener(this);
    }
}
class GooglePlayHeading extends BaseHeading implements DeviceOrientationListener, EventChannel.StreamHandler {
    FusedOrientationProviderClient orientationProviderClient;
    Executor executor;
    DeviceOrientationRequest request;
    EventChannel.EventSink events;
    GooglePlayHeading(Activity activity, DeviceOrientationRequest request, Executor executor) {
        GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
        boolean isPlayServicesAvailable = (apiAvailability.isGooglePlayServicesAvailable(activity) == ConnectionResult.SUCCESS);
        if (isPlayServicesAvailable) {
            orientationProviderClient = LocationServices.getFusedOrientationProviderClient(activity);
        }
        this.executor = executor;
    }
    @Override
    public void dispose() {
        if (orientationProviderClient == null) return;
        orientationProviderClient.removeOrientationUpdates(this);
    }
    @Override
    public void onDeviceOrientationChanged(@NonNull DeviceOrientation deviceOrientation) {
        heading = deviceOrientation.getHeadingDegrees();
        if (deviceOrientation.hasConservativeHeadingErrorDegrees())
            accuracy = deviceOrientation.getConservativeHeadingErrorDegrees();
        else
            accuracy = deviceOrientation.getHeadingErrorDegrees();
        needsCalibration = (accuracy >= 180);

        if (headingListener != null) {
            headingListener.onHeading(this);
        }

        if (events != null) {
            HelperFunctions.passHeading(events, this);
        }
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        if (orientationProviderClient == null) {
            events.error("NO_PLAY_SERVICES", "Google Play Services not available", null);
            events.endOfStream();
            this.events = null;
            return;
        }
        this.events = events;
        orientationProviderClient.requestOrientationUpdates(request, executor, this);
    }

    @Override
    public void onCancel(Object arguments) {
        if (orientationProviderClient == null) return;
        orientationProviderClient.removeOrientationUpdates(this);
    }
}

public class CompassXPlugin implements FlutterPlugin, ActivityAware {
    private LocationManager locationManager;
    private RotationSensorHeading magneticHeading;
    private HeadingProvider trueHeading;
    private EventChannel magneticHeadingChannel, trueHeadingChannel;
    private SensorManager sensorManager;
    private Activity activity;
    public CompassXPlugin() {
    }
    @Override
    public void onAttachedToEngine(FlutterPlugin.FlutterPluginBinding flutterPluginBinding) {
        var context = flutterPluginBinding.getApplicationContext();
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        var bMessenger = flutterPluginBinding.getBinaryMessenger();
        magneticHeadingChannel = new EventChannel(bMessenger, "studio.midoridesign/compassx/magnetic_heading");
        trueHeadingChannel = new EventChannel(bMessenger, "studio.midoridesign/compassx/true_heading");
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public void onDetachedFromEngine(FlutterPlugin.FlutterPluginBinding binding) {
        magneticHeadingChannel.setStreamHandler(null);
        trueHeadingChannel.setStreamHandler(null);
        cleanup();
    }
    @Override
    public void onAttachedToActivity(ActivityPluginBinding binding) {
        activity = binding.getActivity();
        var rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        var headingSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEADING);

        magneticHeading = new RotationSensorHeading(
                sensorManager,
                rotationSensor,
                null);
        magneticHeadingChannel.setStreamHandler(magneticHeading);

        // We need to run on the main thread
        var executor = ContextCompat.getMainExecutor(activity);
        EventChannel.StreamHandler handler = null;
        if (isGooglePlayServicesAvailable()) {
            var request = new DeviceOrientationRequest.Builder(DeviceOrientationRequest.OUTPUT_PERIOD_DEFAULT).build();
            var headingTemp = new GooglePlayHeading(activity, request, executor);
            trueHeading = headingTemp;
            handler = headingTemp;
            Log.i(HelperFunctions.logName, "Google Play Services Available");
        } else if (rotationSensor != null) {
            var headingTemp = new RotationSensorHeading(
                    sensorManager,
                    rotationSensor,
                    locationManager
            );
            trueHeading = headingTemp;
            handler = headingTemp;
            Log.i(HelperFunctions.logName, "Rotation Sensor Available");
        } else if (headingSensor != null) {
            trueHeading = new HeadingSensorHeading(sensorManager, headingSensor);
            Log.i(HelperFunctions.logName, "Heading Sensor Available");
        } else {
            Log.i(HelperFunctions.logName, "No Heading Available");
            // Null heading
            trueHeading = new RotationSensorHeading(sensorManager, null, null);
        }

        trueHeadingChannel.setStreamHandler(handler);

    }
    @Override
    public void onDetachedFromActivity() {
        activity = null;
        cleanup();
    }
    @Override
    public void onReattachedToActivityForConfigChanges(ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }
    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }
    private boolean isGooglePlayServicesAvailable() {
        GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
        return apiAvailability.isGooglePlayServicesAvailable(activity) == ConnectionResult.SUCCESS;
    }
    private void cleanup() {
        if (trueHeading != null) {
            trueHeading.dispose();
            trueHeading = null;
        }

        if (magneticHeading != null) {
            magneticHeading.dispose();
            magneticHeading = null;
        }
    }
}
