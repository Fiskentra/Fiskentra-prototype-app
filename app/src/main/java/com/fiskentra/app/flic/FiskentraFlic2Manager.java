package com.fiskentra.app.flic;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.flic.flic2libandroid.Flic2Button;
import io.flic.flic2libandroid.Flic2ButtonListener;
import io.flic.flic2libandroid.Flic2Manager;
import io.flic.flic2libandroid.Flic2ScanCallback;

/** Owns foreground Flic 2 pairing, reconnect and event translation for Fiskentra v0.5. */
public final class FiskentraFlic2Manager {
    public enum Action { CATCH, WAYPOINT, TACKLE_CHANGE }

    public interface Listener {
        void onStatus(String status);
        void onButtonChanged(String name, String address, boolean connected);
        void onAction(Action action);
        void onStaleEventIgnored();
    }

    private static final long MAX_QUEUED_EVENT_AGE_MS = 15_000L;

    private final Context context;
    private final Listener listener;
    private final Flic2Manager manager;
    private final Set<Flic2Button> listeningButtons = new HashSet<>();

    public FiskentraFlic2Manager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.manager = Flic2Manager.getInstance();
        attachAndConnectPairedButtons();
    }

    public boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public int pairedButtonCount() {
        return manager.getButtons().size();
    }

    public void attachAndConnectPairedButtons() {
        if (!hasPermissions()) {
            listener.onStatus("Nearby devices permission needed");
            return;
        }
        List<Flic2Button> buttons = manager.getButtons();
        if (buttons.isEmpty()) {
            listener.onStatus("No Flic 2 paired with Fiskentra");
            return;
        }
        for (Flic2Button button : buttons) {
            attach(button);
            try {
                button.connect();
                listener.onButtonChanged(displayName(button), button.getBdAddr(),
                        button.getConnectionState() == Flic2Button.CONNECTION_STATE_CONNECTED_READY);
            } catch (SecurityException error) {
                listener.onStatus("Nearby devices permission needed");
                return;
            }
        }
        listener.onStatus("Reconnecting to paired Flic 2…");
    }

    public void pairNewButton() {
        if (!hasPermissions()) {
            listener.onStatus("Nearby devices permission needed");
            return;
        }
        listener.onStatus("Hold the Flic 2 for 6 seconds until it glows, then keep it nearby");
        try {
            manager.startScan(new Flic2ScanCallback() {
                @Override public void onDiscoveredAlreadyPairedButton(Flic2Button button) {
                    attach(button);
                    listener.onStatus("That Flic 2 is already paired with Fiskentra");
                }

                @Override public void onDiscovered(String bdAddr) {
                    listener.onStatus("Flic 2 found · connecting…");
                }

                @Override public void onConnected() {
                    listener.onStatus("Flic 2 connected · securing pairing…");
                }

                @Override public void onAskToAcceptPairRequest() {
                    listener.onStatus("Tap Pair & connect in the Android dialog");
                }

                @Override public void onComplete(int result, int subCode, Flic2Button button) {
                    if (result == Flic2ScanCallback.RESULT_SUCCESS && button != null) {
                        attach(button);
                        listener.onButtonChanged(displayName(button), button.getBdAddr(), true);
                        listener.onStatus("Flic 2 paired · press it to test Fiskentra");
                    } else {
                        listener.onStatus(scanFailureMessage(result, subCode));
                    }
                }
            });
        } catch (SecurityException error) {
            listener.onStatus("Nearby devices permission needed");
        }
    }

    public void close() {
        for (Flic2Button button : listeningButtons) button.removeListener(buttonListener);
        listeningButtons.clear();
    }

    private void attach(Flic2Button button) {
        if (listeningButtons.add(button)) button.addListener(buttonListener);
    }

    private boolean isStaleQueuedEvent(Flic2Button button, boolean wasQueued, long timestamp) {
        return wasQueued && button.getReadyTimestamp() - timestamp > MAX_QUEUED_EVENT_AGE_MS;
    }

    private final Flic2ButtonListener buttonListener = new Flic2ButtonListener() {
        @Override public void onConnect(Flic2Button button) {
            listener.onStatus("Flic 2 connected · preparing button…");
            listener.onButtonChanged(displayName(button), button.getBdAddr(), false);
        }

        @Override public void onReady(Flic2Button button, long timestamp) {
            listener.onButtonChanged(displayName(button), button.getBdAddr(), true);
            listener.onStatus("Flic 2 ready · single, double or hold");
        }

        @Override public void onDisconnect(Flic2Button button) {
            listener.onButtonChanged(displayName(button), button.getBdAddr(), false);
            listener.onStatus("Flic 2 disconnected · reconnecting when available");
        }

        @Override public void onUnpaired(Flic2Button button) {
            listeningButtons.remove(button);
            listener.onButtonChanged(displayName(button), button.getBdAddr(), false);
            listener.onStatus("Flic 2 was unpaired · pair it again");
        }

        @Override public void onFailure(Flic2Button button, int errorCode, int subCode) {
            listener.onStatus("Flic 2 connection error " + errorCode + ":" + subCode + " · retrying");
        }

        @Override public void onButtonSingleOrDoubleClickOrHold(
                Flic2Button button,
                boolean wasQueued,
                boolean lastQueued,
                long timestamp,
                boolean isSingleClick,
                boolean isDoubleClick,
                boolean isHold) {
            if (isStaleQueuedEvent(button, wasQueued, timestamp)) {
                listener.onStaleEventIgnored();
                return;
            }
            if (isHold) listener.onAction(Action.TACKLE_CHANGE);
            else if (isDoubleClick) listener.onAction(Action.WAYPOINT);
            else if (isSingleClick) listener.onAction(Action.CATCH);
        }
    };

    private static String displayName(Flic2Button button) {
        String name = button.getName();
        return name == null || name.trim().isEmpty() ? "Flic 2" : name.trim();
    }

    private static String scanFailureMessage(int result, int subCode) {
        switch (result) {
            case Flic2ScanCallback.RESULT_FAILED_ALREADY_RUNNING:
                return "Flic 2 pairing is already running";
            case Flic2ScanCallback.RESULT_FAILED_BLUETOOTH_OFF:
                return "Turn Bluetooth on, then try pairing again";
            case Flic2ScanCallback.RESULT_FAILED_NO_NEW_BUTTONS_FOUND:
                return "No new Flic 2 found · hold it for 6 seconds and retry";
            case Flic2ScanCallback.RESULT_FAILED_BUTTON_ALREADY_CONNECTED_TO_OTHER_DEVICE:
                return "Flic 2 is busy with another device · disconnect it there and retry";
            case Flic2ScanCallback.RESULT_SYSTEM_PAIRING_DIALOG_NOT_ACCEPTED:
                return "Android pairing was not accepted · try again";
            default:
                return String.format(Locale.US, "Flic 2 pairing failed (%d:%d) · try again", result, subCode);
        }
    }
}
