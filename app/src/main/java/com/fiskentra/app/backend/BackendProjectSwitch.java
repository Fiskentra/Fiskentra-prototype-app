package com.fiskentra.app.backend;

import com.fiskentra.app.data.PointLedger;
import com.fiskentra.app.model.SavedPoint;

/** Rebuild cloud work before the worker starts after moving to an empty server. */
public final class BackendProjectSwitch {
    private BackendProjectSwitch() {}

    public static void requeue(PointLedger ledger) {
        // Receipts and outstanding deletes describe the previous database only.
        // Keep local tombstones, event identities and all point metadata.
        ledger.retry();
        PointLedger.Operation op;
        while ((op = ledger.claim(Long.MAX_VALUE)) != null) {
            ledger.finish(op.operationId, true, "", "Previous backend retired", 0);
        }
        for (SavedPoint point : ledger.snapshot().points) {
            if (point.hasLocation()) ledger.edit(point.id, current -> current);
        }
    }
}
