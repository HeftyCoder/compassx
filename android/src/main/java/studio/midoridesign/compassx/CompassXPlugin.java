package studio.midoridesign.compassx;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.DeviceOrientation;
import com.google.android.gms.location.DeviceOrientationListener;
import com.google.android.gms.location.DeviceOrientationRequest;
import com.google.android.gms.location.FusedOrientationProviderClient;
import com.google.android.gms.location.LocationServices;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.EventChannel;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;

import java.sql.Connection;
import java.util.HashMap;
import java.util.concurrent.Executor;

interface HeadingListener {
    void onHeading(HeadingProvider headingProvider);
}

interface HeadingProvider {
    float getTrueHeading();
    float getAccuracy();
    boolean shouldCalibrate();
    void register(HeadingListener listener);
    void dispose();
}

abstract class BaseHeading implements HeadingProvider {
    float trueHeading;
    float accuracy;
    boolean needsCalibration=false;

    @Override
    public float getTrueHeading() { return trueHeading; }
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
 * Provides the true heading based on Rotation Vector and an external
 * magnetic declination.
**/
class RotationSensorHeading extends BaseHeading implements SensorEventListener {
    SensorManager sensorManager;
    Sensor rotationSensor;
    float declination = 0;
    float[] rotationMatrix = new float[9];
    float[] orientationAngles = new float[3];
    RotationSensorHeading(
            SensorManager sensorManager,
            Sensor rotationSensor)
    {
        this.sensorManager = sensorManager;
        this.rotationSensor = rotationSensor;
        this.sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
    }
    @Override
    public void dispose() {
        this.sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR){
            return;
        }

        SensorManager.getRotationMatrixFromVector(rotationMatrix, orientationAngles);
        float azimuth = (float) Math.toDegrees(orientationAngles[0]);
        float newHeading = (azimuth + declination + 360) % 360;
        float accuracyRadian = event.values[4];
        float newAcc =
                accuracyRadian != -1 ? (float) Math.toDegrees(accuracyRadian) : -1;

        // Guard for small values
        if (Math.abs(trueHeading - newHeading) < 0.1) return;

        trueHeading = newHeading;
        accuracy = newAcc;

        if (headingListener != null) {
            headingListener.onHeading(RotationSensorHeading.this);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (rotationSensor != sensor) return;
        needsCalibration = accuracy != SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
    }
}

class HeadingSensorHeading extends BaseHeading implements SensorEventListener {
    SensorManager sensorManager;
    Sensor headingSensor;
    HeadingSensorHeading(
            SensorManager sensorManager,
            Sensor headingSensor) {
        this.sensorManager = sensorManager;
        this.headingSensor = headingSensor;
        this.sensorManager.registerListener(this, headingSensor, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public void dispose() {
        this.sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float heading = event.values[0];
        float accuracy = event.values[1];

        if (Math.abs(trueHeading - heading) < 0.1){
            return;
        }

        trueHeading = heading;
        this.accuracy = accuracy;

        if (headingListener != null) {
            headingListener.onHeading(this);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (headingSensor != sensor) return;
        needsCalibration = accuracy != SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
    }
}

class GooglePlayHeading extends BaseHeading implements DeviceOrientationListener {
    FusedOrientationProviderClient orientationProviderClient;
    GooglePlayHeading(Activity activity, DeviceOrientationRequest request, Executor executor) {
        orientationProviderClient = LocationServices.getFusedOrientationProviderClient(activity);
        orientationProviderClient.requestOrientationUpdates(request, executor, this);
    }
    @Override
    public void dispose() {
        orientationProviderClient.removeOrientationUpdates(this);
    }
    @Override
    public void onDeviceOrientationChanged(@NonNull DeviceOrientation deviceOrientation) {
        trueHeading = deviceOrientation.getHeadingDegrees();
        if (deviceOrientation.hasConservativeHeadingErrorDegrees())
            accuracy = deviceOrientation.getConservativeHeadingErrorDegrees();
        else
            accuracy = deviceOrientation.getHeadingErrorDegrees();
        needsCalibration = (accuracy >= 180);

        if (headingListener != null) {
            headingListener.onHeading(this);
        }
    }
}


public class CompassXPlugin implements FlutterPlugin, EventChannel.StreamHandler, ActivityAware {
    private EventChannel channel;
    private SensorManager sensorManager;
    private Activity activity;
    private HeadingProvider headingProvider;

    public CompassXPlugin() {
    }

    @Override
    public void onAttachedToEngine(FlutterPlugin.FlutterPluginBinding flutterPluginBinding) {
        var context = flutterPluginBinding.getApplicationContext();
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        channel = new EventChannel(flutterPluginBinding.getBinaryMessenger(),
                "studio.midoridesign/compassx");
        channel.setStreamHandler(this);
    }

    @Override
    public void onDetachedFromEngine(FlutterPlugin.FlutterPluginBinding binding) {
        channel.setStreamHandler(null);
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        var rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        var headingSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEADING);
        var googlePlayAvailable = isGooglePlayServicesAvailable();

        // Early exit
        if (rotationVectorSensor == null && headingSensor == null) {
            earlyExit(events);
            return;
        }

        if (googlePlayAvailable) {

        }
        else if (rotationVectorSensor != null) {
            headingProvider = new RotationSensorHeading(sensorManager, rotationVectorSensor);
        } else if (headingSensor != null) {
            headingProvider = new HeadingSensorHeading(sensorManager, headingSensor);
        }
        // This shouldn't happen. Nevertheless, leaving it here for completeness
        else {
            earlyExit(events);
            return;
        }

        headingProvider.register(new HeadingListener() {
            @Override
            public void onHeading(HeadingProvider headingProvider) {
                events.success(new HashMap<String, Object>() {
                    {
                        put("heading", headingProvider.getTrueHeading());
                        put("accuracy", headingProvider.getAccuracy());
                        put("shouldCalibrate", headingProvider.shouldCalibrate());
                    }
                });
            }
        });

    }

    @Override
    public void onCancel(Object arguments) {
        if (headingProvider != null) {
            headingProvider.dispose();
            headingProvider = null;
        }
    }
    void earlyExit(EventChannel.EventSink events) {
        events.error("SENSOR_NOT_FOUND", "No compass sensor found.", null);
        events.endOfStream();
    }
    @Override
    public void onAttachedToActivity(ActivityPluginBinding binding) {
        activity = binding.getActivity();
    }
    @Override
    public void onDetachedFromActivity() {
        activity = null;
        if (headingProvider != null) {
            headingProvider.dispose();
            headingProvider = null;
        }
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
}
