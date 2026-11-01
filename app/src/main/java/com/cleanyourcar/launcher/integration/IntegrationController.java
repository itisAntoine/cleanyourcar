package com.cleanyourcar.launcher.integration;

import android.content.Context;

import com.cleanyourcar.launcher.compat.CapabilityRegistry;
import com.cleanyourcar.launcher.config.ConfigurationStore;
import com.cleanyourcar.launcher.config.LauncherConfiguration;

/**
 * Owns the single active {@link HeadUnitAdapter} for a UI component. Each command goes through
 * exactly one route (that adapter); state changes only when the adapter reports them.
 */
public final class IntegrationController {

    /** Last state received in this process, so a new UI component does not start blank. */
    private static final Shared PROCESS = new Shared(MediaState.unknown());

    private static final class Shared {
        volatile MediaState media;
        /** User's last successfully sent source selection; never shown as reported state. */
        volatile MediaState.Source requestedSource;
        /** Reverse state of the most recent live controller; UNKNOWN once it stops. */
        volatile MediaState.ReverseState reverse = MediaState.ReverseState.UNKNOWN;

        Shared(MediaState media) {
            this.media = media;
        }
    }

    private final HeadUnitAdapter adapter;
    private final Shared shared;
    private HeadUnitAdapter.Listener uiListener;
    private MediaState.ReverseState reverseState = MediaState.ReverseState.UNKNOWN;
    private HeadUnitAdapter.Health health;
    private boolean started;

    private IntegrationController(HeadUnitAdapter adapter, Shared shared) {
        this.adapter = adapter;
        this.shared = shared;
    }

    /** XYAuto is selected only by an accepted XYAuto profile; legacy arguments are ignored. */
    public static String selectAdapterId(String acceptedAdapterId, boolean legacyUser) {
        return XyAutoAdapter.ADAPTER_ID.equals(acceptedAdapterId)
                ? XyAutoAdapter.ADAPTER_ID : GenericAndroidAdapter.ADAPTER_ID;
    }

    static IntegrationController forAdapterId(String adapterId, MediaState seed,
            XyAutoAdapter.BroadcastTransport broadcasts,
            GenericAndroidAdapter.MediaKeyTransport mediaKeys,
            GenericAndroidAdapter.ReadinessCheck readiness) {
        return create(adapterId, new Shared(seed), broadcasts, mediaKeys, readiness);
    }

    /** Builds the controller for the current configuration with Android transports. */
    public static IntegrationController create(Context context) {
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        LauncherConfiguration config = ConfigurationStore.get(app).load(app);
        String adapterId = selectAdapterId(config.acceptedAdapterId, config.legacyXyAutoUser);
        return create(adapterId, PROCESS, new AndroidBroadcastTransport(),
                new AudioManagerMediaKeyTransport(), new GenericAndroidAdapter.ReadinessCheck() {
                    @Override
                    public boolean isMediaTransportReady() {
                        return isLocallyValidated(app,
                                CapabilityRegistry.Capability.MEDIA_TRANSPORT);
                    }
                });
    }

    private static IntegrationController create(String adapterId, Shared shared,
            XyAutoAdapter.BroadcastTransport broadcasts,
            GenericAndroidAdapter.MediaKeyTransport mediaKeys,
            GenericAndroidAdapter.ReadinessCheck readiness) {
        HeadUnitAdapter adapter = XyAutoAdapter.ADAPTER_ID.equals(adapterId)
                ? new XyAutoAdapter(broadcasts, shared.media)
                : new GenericAndroidAdapter(mediaKeys, readiness);
        return new IntegrationController(adapter, shared);
    }

    private static boolean isLocallyValidated(Context context,
            CapabilityRegistry.Capability capability) {
        try {
            return ConfigurationStore.get(context).load(context).validationKeys
                    .containsKey(capability.id());
        } catch (RuntimeException e) {
            return false;
        }
    }

    public String adapterId() {
        return adapter.id();
    }

    public HeadUnitAdapter.ReverseSupport reverseSupport() {
        return adapter.reverseSupport();
    }

    public void start(Context context, HeadUnitAdapter.Listener listener) {
        if (started) {
            return;
        }
        started = true;
        uiListener = listener;
        adapter.start(context, new HeadUnitAdapter.Listener() {
            @Override
            public void onMediaStateChanged(MediaState state) {
                shared.media = state;
                if (uiListener != null) {
                    uiListener.onMediaStateChanged(state);
                }
            }

            @Override
            public void onReverseStateChanged(MediaState.ReverseState state) {
                reverseState = state;
                shared.reverse = state == null ? MediaState.ReverseState.UNKNOWN : state;
                if (uiListener != null) {
                    uiListener.onReverseStateChanged(state);
                }
            }

            @Override
            public void onHealthChanged(HeadUnitAdapter.Health newHealth, String reason) {
                health = newHealth;
                if (uiListener != null) {
                    uiListener.onHealthChanged(newHealth, reason);
                }
            }
        });
    }

    public void stop() {
        if (!started) {
            return;
        }
        adapter.stop();
        started = false;
        uiListener = null;
        reverseState = MediaState.ReverseState.UNKNOWN;
        shared.reverse = MediaState.ReverseState.UNKNOWN;
        health = null;
    }

    public boolean isStarted() {
        return started;
    }

    public HeadUnitAdapter.CommandResult execute(HeadUnitAdapter.MediaCommand command) {
        if (!started) {
            return HeadUnitAdapter.CommandResult.unavailable(
                    HeadUnitAdapter.CommandResult.REASON_NOT_STARTED);
        }
        HeadUnitAdapter.CommandResult result;
        try {
            result = adapter.execute(command);
        } catch (RuntimeException e) {
            return HeadUnitAdapter.CommandResult.failed("adapter-error: "
                    + XyAutoAdapter.describe(e));
        }
        if (result == null) {
            return HeadUnitAdapter.CommandResult.failed("adapter-error: no result");
        }
        if (result.status == HeadUnitAdapter.CommandResult.Status.SENT) {
            if (command == HeadUnitAdapter.MediaCommand.SELECT_BLUETOOTH) {
                shared.requestedSource = MediaState.Source.BLUETOOTH;
            } else if (command == HeadUnitAdapter.MediaCommand.SELECT_LOCAL) {
                shared.requestedSource = MediaState.Source.LOCAL;
            }
        }
        return result;
    }

    /** Last state reported by the adapter (process-wide for production controllers). */
    public MediaState mediaState() {
        return shared.media;
    }

    public MediaState.ReverseState reverseState() {
        return reverseState;
    }

    /**
     * Reverse state reported to the most recent live controller in this process, or UNKNOWN
     * when that controller stopped; a stale value is never kept as INACTIVE.
     */
    public static MediaState.ReverseState processReverseState() {
        return PROCESS.reverse;
    }

    /** Null before start and after stop. */
    public HeadUnitAdapter.Health health() {
        return health;
    }

    /**
     * Source whose app a "open music" action launches: the reported source, else the user's
     * last sent selection, else Bluetooth (the historical default).
     */
    public MediaState.Source musicLaunchSource() {
        MediaState.Source reported = shared.media.source;
        if (reported != null) {
            return reported;
        }
        MediaState.Source requested = shared.requestedSource;
        return requested != null ? requested : MediaState.Source.BLUETOOTH;
    }
}
