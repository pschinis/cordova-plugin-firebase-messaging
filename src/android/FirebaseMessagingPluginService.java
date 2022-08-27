package by.chemerisuk.cordova.firebase;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.app.PendingIntent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.content.Context;

import java.util.Map;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import static android.content.ContentResolver.SCHEME_ANDROID_RESOURCE;


public class FirebaseMessagingPluginService extends FirebaseMessagingService {
    private static final String TAG = "FCMPluginService";

    public static final String ACTION_FCM_MESSAGE = "by.chemerisuk.cordova.firebase.ACTION_FCM_MESSAGE";
    public static final String EXTRA_FCM_MESSAGE = "by.chemerisuk.cordova.firebase.EXTRA_FCM_MESSAGE";
    public static final String ACTION_FCM_TOKEN = "by.chemerisuk.cordova.firebase.ACTION_FCM_TOKEN";
    public static final String FCM_CLICK_URL = "by.chemerisuk.cordova.firebase.FCM_CLICK_URL";
    public static final String FCM_MSG_CLICK = "by.chemerisuk.cordova.firebase.FCM_MSG_CLICK";
    public static final String EXTRA_FCM_TOKEN = "by.chemerisuk.cordova.firebase.EXTRA_FCM_TOKEN";
    public final static String NOTIFICATION_ICON_KEY = "com.google.firebase.messaging.default_notification_icon";
    public final static String NOTIFICATION_COLOR_KEY = "com.google.firebase.messaging.default_notification_color";
    public final static String NOTIFICATION_CHANNEL_KEY = "com.google.firebase.messaging.default_notification_channel_id";

    private LocalBroadcastManager broadcastManager;
    private NotificationManager notificationManager;
    private int defaultNotificationIcon;
    private int defaultNotificationColor;
    private String defaultNotificationChannel;

    @Override
    public void onCreate() {
        broadcastManager = LocalBroadcastManager.getInstance(this);
        notificationManager = ContextCompat.getSystemService(this, NotificationManager.class);
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(getApplicationContext().getPackageName(), PackageManager.GET_META_DATA);
            defaultNotificationIcon = ai.metaData.getInt(NOTIFICATION_ICON_KEY, ai.icon);
            defaultNotificationChannel = ai.metaData.getString(NOTIFICATION_CHANNEL_KEY, "default");
            defaultNotificationColor = ContextCompat.getColor(this, ai.metaData.getInt(NOTIFICATION_COLOR_KEY));
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "Failed to load meta-data", e);
        } catch(Resources.NotFoundException e) {
            Log.d(TAG, "Failed to load notification color", e);
        }
        // On Android O or greater we need to create a new notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel defaultChannel = notificationManager.getNotificationChannel(defaultNotificationChannel);
            if (defaultChannel == null) {
                notificationManager.createNotificationChannel(
                        new NotificationChannel(defaultNotificationChannel, "Firebase", NotificationManager.IMPORTANCE_HIGH));
            }
        }
    }

    @Override
    public void onNewToken(@NonNull String token) {
        FirebaseMessagingPlugin.sendToken(token);

        Intent intent = new Intent(ACTION_FCM_TOKEN);
        intent.putExtra(EXTRA_FCM_TOKEN, token);
        broadcastManager.sendBroadcast(intent);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        FirebaseMessagingPlugin.sendNotification(remoteMessage);

        Intent intent = new Intent(ACTION_FCM_MESSAGE);
        intent.putExtra(EXTRA_FCM_MESSAGE, remoteMessage);
        broadcastManager.sendBroadcast(intent);

        RemoteMessage.Notification notification = remoteMessage.getNotification();
        Map<String, String> data = remoteMessage.getData();
        String groupId = data.get("group-id");
        String clickUrl = data.get("click-url");

        if(notification == null && (groupId != null || clickUrl != null)) {
            showDataAlert(data);
        } else if (FirebaseMessagingPlugin.isForceShow()) {
            if (notification != null) {
                showAlert(notification);
            }
        }
    }

    private void showDataAlert(Map<String, String> data) {
        String groupIdStr = data.get("group-id");
        String clickUrl = data.get("click-url");
        String msgIdStr = data.get("message-id");
        int msgId = Integer.parseInt(msgIdStr);
        PendingIntent intent = getAppLaunchIntent(clickUrl,msgId);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, getDataNotificationChannel(data))
                .setSound(getNotificationSound("default"))
                .setContentTitle(data.get("title"))
                .setContentText(data.get("body"))
                .setSmallIcon(defaultNotificationIcon)
                .setColor(defaultNotificationColor)
                .setContentIntent(intent);

        if(groupIdStr != null) {
            int groupId = Integer.parseInt(groupIdStr);
            String sumClickUrl = data.get("sum-click-url");
            PendingIntent sumIntent = getAppLaunchIntent((sumClickUrl == null ? clickUrl : sumClickUrl),groupId);

            builder.setGroup(groupIdStr);

            NotificationCompat.Builder sumBuilder = new NotificationCompat.Builder(this, getDataNotificationChannel(data))
                .setSound(getNotificationSound("default"))
                .setContentTitle(data.get("group-name"))
                .setContentText(data.get("group-summary"))
                .setSmallIcon(defaultNotificationIcon)
                .setColor(defaultNotificationColor)
                .setGroup(groupIdStr)
                .setGroupSummary(true)
                .setContentIntent(sumIntent);

                notificationManager.notify(msgId,builder.build());
                notificationManager.notify(groupId,sumBuilder.build());
        } else {
            notificationManager.notify(msgId, builder.build());
        }
    }

    private void showAlert(RemoteMessage.Notification notification) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, getNotificationChannel(notification))
                .setSound(getNotificationSound(notification.getSound()))
                .setContentTitle(notification.getTitle())
                .setContentText(notification.getBody())
                .setGroup(notification.getTag())
                .setSmallIcon(defaultNotificationIcon)
                .setColor(defaultNotificationColor)
                // must set priority to make sure forceShow works properly
                .setPriority(1);

        notificationManager.notify(0, builder.build());
        // dismiss notification to hide icon from status bar automatically
        new Handler(getMainLooper()).postDelayed(() -> {
            notificationManager.cancel(0);
        }, 3000);
    }

    private String getDataNotificationChannel(Map<String, String> data) {
        String channel = data.get("channel");
        if (channel == null) {
            return defaultNotificationChannel;
        } else {
            return channel;
        }
    }

    private String getNotificationChannel(RemoteMessage.Notification notification) {
        String channel = notification.getChannelId();
        if (channel == null) {
            return defaultNotificationChannel;
        } else {
            return channel;
        }
    }

    private Uri getNotificationSound(String soundName) {
        if (soundName == null || soundName.isEmpty()) {
            return null;
        } else if (soundName.equals("default")) {
            return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        } else {
            return Uri.parse(SCHEME_ANDROID_RESOURCE + "://" + getApplicationContext().getPackageName() + "/raw/" + soundName);
        }
    }

    private PendingIntent getAppLaunchIntent(String clickUrl,int code) { 
        Context context = getApplicationContext();
        String  packageName = context.getPackageName();
        Intent  launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        launchIntent.putExtra(FCM_MSG_CLICK, "true");

        if(clickUrl != null) {
            launchIntent.putExtra(FCM_CLICK_URL, clickUrl);
        }

        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(context, code, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return pendingIntent;
    }
}
