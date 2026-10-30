package com.cleanyourcar.navbridge;

import android.Manifest;
import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Visible setup for the optional bridge: independent BLE/LAN opt-in, local pairing token,
 * explicit start/stop, runtime permissions and the real per-transport state including errors.
 */
public class BridgeSettingsActivity extends Activity implements NavBridgeService.StatusObserver {
    private static final int REQUEST_PERMISSIONS = 1;

    private TextView pauseNotice;
    private TextView statusView;
    private TextView credentialView;
    private TextView addressView;
    private CheckBox bleBox;
    private CheckBox lanBox;
    private CheckBox autoStartBox;
    private boolean binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text(getString(R.string.app_name), 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(text(getString(R.string.bridge_intro), 15));

        pauseNotice = text(getString(R.string.bridge_pause_body), 15);
        root.addView(pauseNotice);

        statusView = text("", 16);
        statusView.setPadding(0, dp(12), 0, dp(12));
        root.addView(statusView);

        bleBox = checkBox(getString(R.string.bridge_enable_ble));
        lanBox = checkBox(getString(R.string.bridge_enable_lan));
        autoStartBox = checkBox(getString(R.string.bridge_enable_autostart));
        root.addView(bleBox);
        root.addView(lanBox);
        root.addView(autoStartBox);
        CheckBox.OnCheckedChangeListener onChange = (button, checked) -> {
            if (binding) return;
            BridgeConfiguration config = BridgeConfiguration.load(this);
            BridgeConfiguration updated = config
                    .withBleEnabled(bleBox.isChecked())
                    .withLanEnabled(lanBox.isChecked())
                    .withAutoStartEnabled(autoStartBox.isChecked());
            updated.save(this);
            if (button != autoStartBox) NavBridgeService.requestStop(this);
            refresh();
        };
        bleBox.setOnCheckedChangeListener(onChange);
        lanBox.setOnCheckedChangeListener(onChange);
        autoStartBox.setOnCheckedChangeListener(onChange);

        root.addView(section(getString(R.string.bridge_token_title)));
        root.addView(text(getString(R.string.bridge_token_help), 14));
        credentialView = text("", 15);
        credentialView.setTypeface(Typeface.MONOSPACE);
        credentialView.setTextIsSelectable(true);
        root.addView(credentialView);
        LinearLayout tokenRow = row();
        tokenRow.addView(button(getString(R.string.bridge_token_generate), v -> generateToken()));
        tokenRow.addView(button(getString(R.string.bridge_token_revoke), v -> revokeToken()));
        root.addView(tokenRow);

        root.addView(section(getString(R.string.bridge_lan_title)));
        addressView = text("", 14);
        addressView.setTextIsSelectable(true);
        root.addView(addressView);
        root.addView(text(getString(R.string.bridge_lan_warning), 14));

        LinearLayout actions = row();
        actions.addView(button(getString(R.string.bridge_permissions), v -> requestBridgePermissions()));
        actions.addView(button(getString(R.string.bridge_start), v -> startBridge()));
        actions.addView(button(getString(R.string.bridge_stop), v -> NavBridgeService.requestStop(this)));
        root.addView(actions);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        NavBridgeService.setObserver(this);
        refresh();
    }

    @Override
    protected void onPause() {
        NavBridgeService.setObserver(null);
        super.onPause();
    }

    @Override
    public void onStatus(NavBridgeService.Status status) {
        refresh();
    }

    private void refresh() {
        BridgeConfiguration config = BridgeConfiguration.load(this);
        binding = true;
        bleBox.setChecked(config.bleEnabled);
        lanBox.setChecked(config.lanEnabled);
        autoStartBox.setChecked(config.autoStartEnabled);
        binding = false;
        pauseNotice.setVisibility(config.isConfigured() ? View.GONE : View.VISIBLE);
        credentialView.setText(config.hasCredential()
                ? config.bearerValue() : getString(R.string.bridge_token_none));
        addressView.setText(lanAddresses());
        statusView.setText(describe(NavBridgeService.latestStatus(), config));
    }

    private CharSequence describe(NavBridgeService.Status status, BridgeConfiguration config) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(status.running ? R.string.bridge_running : R.string.bridge_stopped));
        sb.append('\n').append(getString(R.string.bridge_status_ble,
                NavBridgeService.stateLabel(this, status.ble), detail(status.bleDetail)));
        sb.append('\n').append(getString(R.string.bridge_status_lan,
                NavBridgeService.stateLabel(this, status.lan), detail(status.lanDetail)));
        for (BridgeLifecyclePolicy.Reason reason : status.reasons) {
            sb.append("\n• ").append(reasonText(reason));
        }
        String error = NavBridgeService.lastError(this);
        if (!status.running && error != null) {
            sb.append('\n').append(getString(R.string.bridge_last_error, error));
        }
        if (!config.isConfigured()) sb.append('\n').append(getString(R.string.bridge_not_configured));
        return sb;
    }

    private static String detail(String detail) {
        return detail == null || detail.isEmpty() ? "" : " (" + detail + ")";
    }

    private String reasonText(BridgeLifecyclePolicy.Reason reason) {
        switch (reason) {
            case NOT_OPTED_IN:
                return getString(R.string.bridge_reason_not_opted_in);
            case NO_CREDENTIAL:
                return getString(R.string.bridge_reason_no_credential);
            case AUTO_START_NOT_CONSENTED:
                return getString(R.string.bridge_reason_autostart);
            case NOTIFICATION_PERMISSION_MISSING:
                return getString(R.string.bridge_reason_notifications);
            case BLUETOOTH_PERMISSION_MISSING:
                return getString(R.string.bridge_reason_bt_permission);
            case BLUETOOTH_UNAVAILABLE:
                return getString(R.string.bridge_reason_bt_unavailable);
            case BLUETOOTH_DISABLED:
                return getString(R.string.bridge_reason_bt_disabled);
            case BLE_ADVERTISING_UNSUPPORTED:
                return getString(R.string.bridge_reason_ble_advertising);
            case LAN_LIFECYCLE_UNAVAILABLE:
                return getString(R.string.bridge_reason_lan_lifecycle);
            default:
                return reason.name();
        }
    }

    private void startBridge() {
        BridgeLifecyclePolicy.StartDecision decision = NavBridgeService.requestStart(this, true);
        if (!decision.allowed()) {
            Toast.makeText(this, R.string.bridge_start_denied, Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    private void generateToken() {
        BridgeAuthorization.Credential credential = BridgeAuthorization.newCredential(new SecureRandom());
        BridgeConfiguration.load(this).withCredential(credential.clientId, credential.token).save(this);
        NavBridgeService.requestStop(this);
        Toast.makeText(this, R.string.bridge_token_generated, Toast.LENGTH_LONG).show();
        refresh();
    }

    private void revokeToken() {
        BridgeConfiguration.load(this).withoutCredential().save(this);
        NavBridgeService.requestStop(this);
        refresh();
    }

    private void requestBridgePermissions() {
        List<String> wanted = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted.add(Manifest.permission.BLUETOOTH_CONNECT);
            wanted.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (wanted.isEmpty()) {
            Toast.makeText(this, R.string.bridge_permissions_none_needed, Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(wanted.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    private String lanAddresses() {
        StringBuilder sb = new StringBuilder();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append("http://").append(address.getHostAddress()).append(':')
                                .append(NavBridgeProtocol.PORT).append(NavBridgeProtocol.HTTP_PATH);
                    }
                }
            }
        } catch (Exception ignored) {
            // Address discovery is informational only.
        }
        return sb.length() == 0 ? getString(R.string.bridge_lan_no_address) : sb.toString();
    }

    private TextView text(String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private TextView section(String value) {
        TextView view = text(value, 18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(16), 0, dp(4));
        return view;
    }

    private CheckBox checkBox(String label) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        box.setMinHeight(dp(48));
        return box;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setMinHeight(dp(48));
        button.setMinWidth(dp(48));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        button.setLayoutParams(params);
        return button;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
