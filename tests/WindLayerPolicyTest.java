import com.fiskentra.app.model.WindLayerPolicy;

public final class WindLayerPolicyTest {
    private static int checks;
    private static void check(boolean ok, String description) {
        checks++;
        if (!ok) throw new AssertionError(description);
    }
    public static void main(String[] args) {
        long now = 1_800_000_000_000L, minute = 60_000L;
        check(WindLayerPolicy.flowBearing(0) == 180, "north wind must travel south");
        check(WindLayerPolicy.flowBearing(90) == 270, "east wind must travel west");
        check(WindLayerPolicy.flowBearing(270) == 90, "west wind must travel east");
        check(WindLayerPolicy.flowBearing(360) == 180, "full-circle north wraps");
        check(WindLayerPolicy.usable(now, 0, 0, now), "calm is valid weather");
        check(WindLayerPolicy.usable(now - 60 * minute, 12, 360, now), "last permitted saved sample");
        check(!WindLayerPolicy.usable(now - 61 * minute, 12, 45, now), "expired saved weather is hidden");
        check(!WindLayerPolicy.usable(now + 6 * minute, 12, 45, now), "future-dated weather is hidden");
        check(!WindLayerPolicy.usable(now, Double.NaN, 45, now), "missing speed is not calm wind");
        check(!WindLayerPolicy.usable(now, -1, 45, now), "negative speed is invalid");
        check(!WindLayerPolicy.usable(now, 12, 361, now), "bad direction is invalid");
        check(!WindLayerPolicy.due(now + 14 * minute, now, true), "GPS ticks reuse successful sample");
        check(WindLayerPolicy.due(now + 15 * minute, now, true), "refresh current wind every fifteen minutes");
        check(!WindLayerPolicy.due(now + 59_999, now, false), "network failure does not retry per GPS tick");
        check(WindLayerPolicy.due(now + minute, now, false), "network failure can retry after a minute");
        check(WindLayerPolicy.due(now - minute, now, true), "clock rollback does not freeze refresh");
        check(WindLayerPolicy.indicatorFits(144,136,600,120,false),"wind instrument fits below compass and above bottom controls");
        check(WindLayerPolicy.indicatorFits(144,136,400,120,false),"exact bottom clearance is allowed");
        check(!WindLayerPolicy.indicatorFits(144,136,399,120,false),"small viewport hides wind instead of covering navigation");
        check(!WindLayerPolicy.indicatorFits(144,136,600,120,true),"open sheet keeps wind out of search and detail controls");
        check(!WindLayerPolicy.indicatorFits(144,0,600,120,false),"disabled or unmeasured indicator does not reserve a viewport");
        check(!WindLayerPolicy.indicatorFits(144,136,250,0,false),"keyboard-reduced viewport cannot overlap the instrument");
        check(WindLayerPolicy.indicatorFits(144,136,600,120,false),"instrument can return after the sheet or keyboard closes");
        System.out.println("WindLayerPolicy: " + checks + " checks passed");
    }
}
