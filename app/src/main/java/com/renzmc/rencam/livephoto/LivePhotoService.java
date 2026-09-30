package com.renzmc.rencam.livephoto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.renzmc.rencam.MyDebug;
import com.renzmc.rencam.R;

/**
 * RenCam Live Photo: a tiny foreground service that keeps the process alive while a Live Photo is
 * being finalised (trimming the buffer, extracting the cover, saving the still and packaging the
 * Motion Photo).
 *
 * <p>Why this exists: the finalisation runs on a background thread, but Android may freeze or kill
 * a backgrounded process at any moment - especially on modern versions with aggressive battery
 * management. If that happened mid-way through packaging, the still would be left as a plain JPEG
 * (the "sometimes it isn't a Live Photo" bug). Running a foreground service with an ongoing
 * notification tells Android the app is actively doing something the user asked for, so the work is
 * allowed to finish even if the user leaves the app.</p>
 *
 * <p>Foreground services only exist from Android 8.0 (API 26); on older versions the process is not
 * killed as aggressively, so we simply don't start the service there. Everything is guarded and
 * wrapped in try/catch so it can never crash the camera.</p>
 */
public class LivePhotoService extends Service {
    private static final String TAG = "LivePhotoService";

    private static final String CHANNEL_ID = "rencam_live_photo";
    private static final int NOTIFICATION_ID = 1001;

    /** Starts (or refreshes) the foreground service. Safe to call repeatedly. */
    public static void start(Context context) {
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(context, LivePhotoService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            }
            else {
                // Pre-Oreo there is no foreground-service concept; a normal service is enough (and
                // the OS does not kill background processes as eagerly).
                context.startService(intent);
            }
        }
        catch (Exception e) {
            // Never let a service-start failure break the capture - finalisation still runs on its
            // own background thread, this is only a best-effort "keep alive" helper.
            Log.e(TAG, "failed to start LivePhotoService", e);
        }
    }

    /** Stops the foreground service once there is no more Live Photo work pending. */
    public static void stop(Context context) {
        if (context == null) {
            return;
        }
        try {
            context.stopService(new Intent(context, LivePhotoService.class));
        }
        catch (Exception e) {
            Log.e(TAG, "failed to stop LivePhotoService", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (MyDebug.LOG)
            Log.d(TAG, "onStartCommand");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                createNotificationChannel();
                Notification notification = buildNotification();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                }
                else {
                    startForeground(NOTIFICATION_ID, notification);
                }
            }
            catch (Exception e) {
                Log.e(TAG, "failed to enter foreground", e);
            }
        }
        // Don't restart automatically if the process is killed - a stale notification with no work
        // behind it would be confusing.
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.preference_category_live_photo),
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription(getString(R.string.live_photo_processing));
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        }
        else {
            //noinspection deprecation
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.live_photo_processing))
                .setSmallIcon(R.drawable.ic_photo_camera_white_48dp)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        return builder.build();
    }
}
