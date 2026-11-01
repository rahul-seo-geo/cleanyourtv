package com.gathin.news;

import java.util.ArrayList;
import java.util.List;

final class LauncherTargets {

    interface LaunchProbe { boolean isLaunchable(String pkg); }

    static final class Target {
        final String pkg;
        final String label;

        Target(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    static List<Target> parse(Settings settings, int maxSlots) {
        List<Target> targets = new ArrayList<>();
        for (String key : settings.keysWithPrefix("row")) {
            if (targets.size() >= maxSlots) break;
            String value = settings.get(key);
            if (value.isEmpty()) continue;
            int bar = value.indexOf('|');
            String pkg = (bar < 0 ? value : value.substring(0, bar)).trim();
            if (pkg.isEmpty()) continue;
            String label = bar < 0 ? null : value.substring(bar + 1).trim();
            targets.add(new Target(pkg, label == null || label.isEmpty() ? null : label));
        }
        return targets;
    }

    static List<Target> filter(List<Target> configured, LaunchProbe probe) {
        List<Target> targets = new ArrayList<>();
        for (Target target : configured) if (probe.isLaunchable(target.pkg)) targets.add(target);
        return targets;
    }

    private LauncherTargets() { }
}
