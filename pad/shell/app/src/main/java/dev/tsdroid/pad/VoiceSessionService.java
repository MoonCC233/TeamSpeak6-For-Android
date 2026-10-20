package dev.tsdroid.pad;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps the process alive while the WebView holds a live microphone/voice
 * session, so Android does not freeze the WebRTC stack in the background.
 *
 * <p>The service owns no media itself. The WebView keeps the actual audio
 * track; this service only holds the foreground slot, the microphone
 * foreground-service type and the audio focus request. It is started/stopped
 * from {@code MainActivity} when the injected WebView hook sees the
 * microphone track become live/ended.</p>
 *
 * <p>License: AGPL-3.0-only, matching the vendored {@code pad/} upstream.</p>
 */
public final class VoiceSessionService extends Service {
    private static final String TAG = "TS6Pad";
    public static final String ACTION_START = "dev.tsdroid.pad.action.VOICE_START";
    private static final String CHANNEL_ID = "ts6pad_voice";
    private static final int NOTIFICATION_ID = 6001;

    /** Same-process listener used by the activity to mirror focus changes into the page. */
    public interface FocusListener {
        void onAudioFocusChanged(boolean hasFocus, boolean transientLoss);
    }

    private static volatile FocusListener sFocusListener;
    private static volatile boolean sRunning;

    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean focusHeld;

    public static boolean isRunning() {
        return sRunning;
    }

    public static void setFocusListener(FocusListener listener) {
        sFocusListener = listener;
    }

    public static void clearFocusListener(FocusListener listener) {
        if (sFocusListener == listener) {
            sFocusListener = null;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!sRunning) {
            try {
                startForegroundCompat();
                sRunning = true;
                requestAudioFocus();
            } catch (RuntimeException exception) {
                Log.e(TAG, "Unable to enter voice foreground state", exception);
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        abandonAudioFocus();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressLint("InlinedApi")
    private void startForegroundCompat() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.voice_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.voice_channel_description));
        channel.setShowBadge(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_voice)
                .setContentTitle(getString(R.string.voice_notification_title))
                .setContentText(getString(R.string.voice_notification_text))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private void requestAudioFocus() {
        if (audioManager == null) {
            return;
        }
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(this::onAudioFocusChange)
                .build();
        int result = audioManager.requestAudioFocus(focusRequest);
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        Log.i(TAG, "Voice service started; audio focus granted=" + focusHeld);
    }

    private void abandonAudioFocus() {
        if (audioManager != null && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
        focusRequest = null;
        focusHeld = false;
    }

    private void onAudioFocusChange(int focusChange) {
        boolean hasFocus = focusChange == AudioManager.AUDIOFOCUS_GAIN;
        boolean transientLoss = focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK;
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            focusHeld = false;
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            focusHeld = true;
        }
        Log.i(TAG, "Audio focus changed: change=" + focusChange
                + " hasFocus=" + hasFocus + " transient=" + transientLoss);
        FocusListener listener = sFocusListener;
        if (listener != null) {
            listener.onAudioFocusChanged(hasFocus, transientLoss);
        }
    }
}
