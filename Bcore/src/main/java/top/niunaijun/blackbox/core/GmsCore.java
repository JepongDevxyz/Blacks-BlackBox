package top.niunaijun.blackbox.core;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.utils.DiagLog;


public class GmsCore {
    private static final String TAG = "GmsCore";

    private static final HashSet<String> GOOGLE_APP = new HashSet<>();
    private static final HashSet<String> GOOGLE_SERVICE = new HashSet<>();
    public static final String GMS_PKG = "com.google.android.gms";
    public static final String GSF_PKG = "com.google.android.gsf";
    public static final String VENDING_PKG = "com.android.vending";

    static {
        GOOGLE_APP.add(VENDING_PKG);
        GOOGLE_APP.add("com.google.android.play.games");
        GOOGLE_APP.add("com.google.android.wearable.app");
        GOOGLE_APP.add("com.google.android.wearable.app.cn");

        
        GOOGLE_SERVICE.add(GMS_PKG);
        GOOGLE_SERVICE.add(GSF_PKG);
        GOOGLE_SERVICE.add("com.google.android.gsf.login");
        GOOGLE_SERVICE.add("com.google.android.backuptransport");
        GOOGLE_SERVICE.add("com.google.android.backup");
        GOOGLE_SERVICE.add("com.google.android.configupdater");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.contacts");
        GOOGLE_SERVICE.add("com.google.android.feedback");
        GOOGLE_SERVICE.add("com.google.android.onetimeinitializer");
        GOOGLE_SERVICE.add("com.google.android.partnersetup");
        GOOGLE_SERVICE.add("com.google.android.setupwizard");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.calendar");
    }

    public static boolean isGoogleService(String packageName) {
        return GOOGLE_SERVICE.contains(packageName);
    }

    public static boolean isGoogleAppOrService(String str) {
        return GOOGLE_APP.contains(str) || GOOGLE_SERVICE.contains(str);
    }

    private static InstallResult installPackages(Set<String> list, int userId) {
        // DevxyzClone 1.0.10: per-package diagnostics + continue-on-error.
        // The old design returned on the FIRST failure and installGApps then
        // rolled back (uninstalled) every package that HAD installed, so one
        // dead legacy package silently killed all of Google services and the
        // only trace was "ready=false". Now every attempt is written to
        // DiagLog with its real error, and the rest still install.
        BlackBoxCore blackBoxCore = BlackBoxCore.get();
        StringBuilder failures = new StringBuilder();
        for (String packageName : list) {
            try {
                if (blackBoxCore.isInstalled(packageName, userId)) {
                    DiagLog.log("gms-install skip pkg=" + packageName + " reason=already-installed");
                    continue;
                }
            } catch (Throwable t) {
                DiagLog.log("gms-install isInstalled-check exception pkg=" + packageName + " : " + t);
            }
            try {
                BlackBoxCore.getContext().getPackageManager().getApplicationInfo(packageName, 0);
            } catch (Throwable e) {
                DiagLog.log("gms-install skip pkg=" + packageName + " reason=not-on-host");
                continue;
            }
            InstallResult installResult;
            try {
                installResult = blackBoxCore.installPackageAsUser(packageName, userId);
            } catch (Throwable t) {
                DiagLog.log("gms-install pkg=" + packageName + " exception=" + t);
                if (failures.length() > 0) failures.append("; ");
                failures.append(packageName).append(" threw ").append(t);
                continue;
            }
            DiagLog.log("gms-install pkg=" + packageName + " success=" + installResult.success
                    + " msg=" + installResult.msg);
            if (!installResult.success) {
                if (failures.length() > 0) failures.append("; ");
                failures.append(packageName).append(": ").append(installResult.msg);
            }
        }
        if (failures.length() == 0) {
            return new InstallResult();
        }
        return new InstallResult().installError(failures.toString());
    }

    private static void uninstallPackages(Set<String> list, int userId) {
        BlackBoxCore blackBoxCore = BlackBoxCore.get();
        for (String packageName : list) {
            blackBoxCore.uninstallPackageAsUser(packageName, userId);
        }
    }

    public static InstallResult installGApps(int userId) {
        Set<String> googleApps = new HashSet<>();

        googleApps.addAll(GOOGLE_SERVICE);
        googleApps.addAll(GOOGLE_APP);

        // DevxyzClone 1.0.10: host split info for the two big Google packages
        // (evidence for whether clones see the host's split APKs), and NO
        // rollback on partial failure anymore.
        for (String probe : new String[]{GMS_PKG, VENDING_PKG}) {
            try {
                android.content.pm.ApplicationInfo ai = BlackBoxCore.getContext()
                        .getPackageManager().getApplicationInfo(probe, 0);
                int splits = ai.splitSourceDirs != null ? ai.splitSourceDirs.length : 0;
                DiagLog.log("gms-install hostinfo pkg=" + probe + " splits=" + splits
                        + " src=" + ai.sourceDir);
            } catch (Throwable t) {
                DiagLog.log("gms-install hostinfo pkg=" + probe + " exception=" + t);
            }
        }
        InstallResult installResult = installPackages(googleApps, userId);
        DiagLog.log("gms-install done userId=" + userId + " success=" + installResult.success
                + " msg=" + installResult.msg
                + " gmsInstalled=" + isInstalledGoogleService(userId));
        return installResult;
    }

    public static void uninstallGApps(int userId) {
        uninstallPackages(GOOGLE_SERVICE, userId);
        uninstallPackages(GOOGLE_APP, userId);
    }

    public static boolean hasGmsTraces(int userId) {
        BlackBoxCore blackBoxCore = BlackBoxCore.get();

        Set<String> known = new HashSet<>();
        known.addAll(GOOGLE_SERVICE);
        known.addAll(GOOGLE_APP);

        for (String pkg : known) {
            try {
                if (blackBoxCore.isInstalled(pkg, userId)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            List<PackageInfo> installed = blackBoxCore.getInstalledPackages(0, userId);
            for (PackageInfo pi : installed) {
                if (pi == null || pi.packageName == null) {
                    continue;
                }
                String pkg = pi.packageName;
                if (VENDING_PKG.equals(pkg) || pkg.startsWith("com.google.android.")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    /**
     * Best-effort cleanup: clears package data and uninstalls known Google packages
     * for the given virtual user. Intended for "wipe" UX.
     */
    public static void wipeGApps(int userId) {
        BlackBoxCore blackBoxCore = BlackBoxCore.get();

        Set<String> toRemove = new HashSet<>();
        toRemove.addAll(GOOGLE_SERVICE);
        toRemove.addAll(GOOGLE_APP);

        try {
            List<PackageInfo> installed = blackBoxCore.getInstalledPackages(0, userId);
            for (PackageInfo pi : installed) {
                if (pi == null || pi.packageName == null) {
                    continue;
                }
                String pkg = pi.packageName;
                if (VENDING_PKG.equals(pkg) || pkg.startsWith("com.google.android.")) {
                    toRemove.add(pkg);
                }
            }
        } catch (Throwable ignored) {
        }

        for (String pkg : toRemove) {
            try {
                blackBoxCore.clearPackage(pkg, userId);
            } catch (Throwable ignored) {
            }
        }

        for (String pkg : toRemove) {
            try {
                blackBoxCore.uninstallPackageAsUser(pkg, userId);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void remove(String packageName) {
        GOOGLE_SERVICE.remove(packageName);
        GOOGLE_APP.remove(packageName);
    }


    public static boolean isSupportGms() {
        try {
            BlackBoxCore.getPackageManager().getPackageInfo(GMS_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return false;
    }

    public static boolean isInstalledGoogleService(int userId) {
        return BlackBoxCore.get().isInstalled(GMS_PKG, userId);
    }
}