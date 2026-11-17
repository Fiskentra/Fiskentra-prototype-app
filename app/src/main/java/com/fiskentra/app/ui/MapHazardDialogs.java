package com.fiskentra.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.fiskentra.app.R;
import com.fiskentra.app.data.MapHazardStore;
import com.fiskentra.app.model.MapHazard;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Map dialogs for locally stored danger points and zones. */
final class MapHazardDialogs {
    private final Activity activity;
    private final MapTilerMapView map;
    private final MapHazardStore store;
    private final Runnable changed;
    private AlertDialog activeDialog;

    MapHazardDialogs(Activity activity, MapTilerMapView map, Runnable changed) {
        this.activity = activity;
        this.map = map;
        this.changed = changed;
        store = MapHazardStore.get(activity);
    }

    void refresh() {
        map.setHazards(store.snapshot());
    }

    void showList(Consumer<Boolean> placeOnMap) {
        List<MapHazard> hazards = store.snapshot();
        List<String> labels = new ArrayList<>();
        labels.add(activity.getString(R.string.map_hazard_add_point));
        labels.add(activity.getString(R.string.map_hazard_add_zone));
        for (MapHazard hazard : hazards) {
            labels.add(activity.getString(hazard.isZone() ? R.string.map_hazard_list_zone
                    : R.string.map_hazard_list_point, hazard.name));
        }
        show(new AlertDialog.Builder(activity).setTitle(R.string.map_hazards)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which < 2) {
                        placeOnMap.accept(which == 1);
                    } else {
                        MapHazard hazard = hazards.get(which - 2);
                        if (hazard.isPolygon()) {
                            MapHazard.Vertex corner = hazard.vertices.get(0);
                            map.lookAt(corner.latitude, corner.longitude);
                        } else {
                            map.lookAt(hazard.latitude, hazard.longitude);
                        }
                        showDetails(hazard);
                    }
                }).setNegativeButton(R.string.map_close, null).create());
    }

    void addPoint(double latitude, double longitude) {
        addName(false, name -> store.add(name, latitude, longitude, 0), () -> {});
    }

    void addPolygon(List<MapHazard.Vertex> vertices, Runnable saved) {
        List<MapHazard.Vertex> boundary = new ArrayList<>(vertices);
        addName(true, name -> store.addPolygon(name, boundary), saved);
    }

    private void addName(boolean zone, Consumer<String> save, Runnable saved) {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        form.setPadding(padding, 0, padding, 0);

        EditText name = new EditText(activity);
        name.setHint(R.string.map_hazard_name);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MapHazard.MAX_NAME_LENGTH)});
        form.addView(name);

        if (zone) {
            TextView summary = new TextView(activity);
            summary.setText(activity.getString(R.string.map_hazard_polygon_details, MapHazard.WARNING_DISTANCE_METERS));
            form.addView(summary);
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(zone ? R.string.map_hazard_add_zone : R.string.map_hazard_add_point)
                .setView(form).setNegativeButton(R.string.map_cancel, null)
                .setPositiveButton(R.string.data_save, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String title = name.getText().toString().trim();
                    if (title.isEmpty()) {
                        name.setError(activity.getString(R.string.map_hazard_name_required));
                        return;
                    }
                    try {
                        save.accept(title);
                    } catch (IllegalArgumentException | IllegalStateException failure) {
                        Toast.makeText(activity, R.string.map_save_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.dismiss();
                    refresh();
                    changed.run();
                    saved.run();
                }));
        show(dialog);
    }

    void showDetails(MapHazard hazard) {
        String message;
        if (hazard.isPolygon()) {
            message = activity.getString(R.string.map_hazard_polygon_details, MapHazard.WARNING_DISTANCE_METERS);
        } else if (hazard.isZone()) {
            message = activity.getString(R.string.map_hazard_zone_details, hazard.radiusMeters, MapHazard.WARNING_DISTANCE_METERS);
        } else {
            message = activity.getString(R.string.map_hazard_point_details, MapHazard.WARNING_DISTANCE_METERS);
        }
        show(new AlertDialog.Builder(activity).setTitle(hazard.name)
                .setMessage(message + "\n\n" + activity.getString(R.string.map_hazard_alert_permissions))
                .setNegativeButton(R.string.map_hazard_delete, (dialog, which) -> confirmDelete(hazard))
                .setPositiveButton(R.string.map_close, null).create());
    }

    private void confirmDelete(MapHazard hazard) {
        show(new AlertDialog.Builder(activity).setTitle(R.string.map_hazard_delete)
                .setMessage(activity.getString(R.string.map_hazard_confirm_delete, hazard.name))
                .setNegativeButton(R.string.map_cancel, null)
                .setPositiveButton(R.string.map_hazard_delete_action, (dialog, which) -> {
                    try {
                        if (!store.delete(hazard.id)) return;
                    } catch (IllegalStateException failure) {
                        Toast.makeText(activity, R.string.map_hazard_delete_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    refresh();
                    changed.run();
                }).create());
    }

    private void show(AlertDialog dialog) {
        close();
        activeDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (activeDialog == dialog) activeDialog = null;
        });
        dialog.show();
    }

    void close() {
        if (activeDialog != null) activeDialog.dismiss();
        activeDialog = null;
    }
}
