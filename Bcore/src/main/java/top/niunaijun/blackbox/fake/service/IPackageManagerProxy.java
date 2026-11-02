package top.niunaijun.blackbox.fake.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import black.android.app.BRActivityThread;
import black.android.app.BRContextImpl;
import black.android.app.ContextImpl;
import black.android.content.pm.BRPackageManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.env.AppSystemEnv;
import top.niunaijun.blackbox.core.env.SamsungHealthCompat;
import top.niunaijun.blackbox.fake.FakeCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.service.base.PkgMethodProxy;
import top.niunaijun.blackbox.fake.service.base.ValueMethodProxy;
import top.niunaijun.blackbox.media.BlackBoxMediaContract;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.DiagLog;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;
import top.niunaijun.blackbox.utils.compat.ParceledListSliceCompat;


public class IPackageManagerProxy extends BinderInvocationStub {
    public static final String TAG = "PackageManagerStub";

    private static final String PKG_SAMSUNG_HEALTH = "com.sec.android.app.shealth";
    private static final String PKG_SAMSUNG_ACCOUNT = "com.osp.app.signin";

    private static boolean shouldHideHostSamsungAccountForSamsungHealth(String queriedPackageName) {
        if (!PKG_SAMSUNG_ACCOUNT.equals(queriedPackageName)) {
            return false;
        }
        try {
            String callerPackage = BActivityThread.getAppPackageName();
            if (!PKG_SAMSUNG_HEALTH.equals(callerPackage)) {
                return false;
            }
            int userId = BActivityThread.getUserId();
            return !SamsungHealthCompat.isHostSamsungAccountFallbackEnabled(userId, callerPackage);
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean shouldHideHostSamsungAccountForSamsungHealth(Intent intent) {
        if (intent == null) {
            return false;
        }
        try {
            String targetPackage = intent.getPackage();
            if (targetPackage == null) {
                ComponentName cn = intent.getComponent();
                if (cn != null) {
                    targetPackage = cn.getPackageName();
                }
            }
            return shouldHideHostSamsungAccountForSamsungHealth(targetPackage);
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean shouldHideHostSamsungAccountForSamsungHealthCaller() {
        try {
            String callerPackage = BActivityThread.getAppPackageName();
            if (!PKG_SAMSUNG_HEALTH.equals(callerPackage)) {
                return false;
            }
            int userId = BActivityThread.getUserId();
            return !SamsungHealthCompat.isHostSamsungAccountFallbackEnabled(userId, callerPackage);
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static List<ResolveInfo> filterOutSamsungAccountActivities(List<ResolveInfo> resolves) {
        if (resolves == null || resolves.isEmpty()) {
            return resolves;
        }
        ArrayList<ResolveInfo> filtered = new ArrayList<>(resolves.size());
        for (ResolveInfo ri : resolves) {
            if (ri == null) {
                continue;
            }
            ActivityInfo ai = ri.activityInfo;
            if (ai != null && PKG_SAMSUNG_ACCOUNT.equals(ai.packageName)) {
                continue;
            }
            filtered.add(ri);
        }
        return filtered;
    }

    private static final String PKG_FACEBOOK_KATANA = "com.facebook.katana";

    // DevxyzClone fix (round 2): hide the REAL Facebook app from virtual apps.
    // Facebook SDKs inside a clone prefer the native Katana flow when the real
    // Facebook app is visible; that flow validates the CALLING app from OUTSIDE
    // the container, where the caller is the host's proxy activity, so the key
    // hash Facebook computes is the host's and login is rejected ("Invalid key
    // hash" / "Something went wrong"). When Katana is invisible, SDKs fall back
    // to the web dialog flow, whose identity checks run in-process through the
    // virtual PackageManager and pass with the cloned app's own signatures.
    // The cloned Facebook app itself still sees itself (caller == katana), and
    // the host process is unaffected (caller == host).
    private static boolean shouldHideFacebookApp(String queriedPackageName) {
        if (!PKG_FACEBOOK_KATANA.equals(queriedPackageName)) {
            return false;
        }
        return isFacebookAppHiddenFromCurrentApp();
    }

    private static boolean shouldHideFacebookApp(Intent intent) {
        if (intent == null) {
            return false;
        }
        try {
            String targetPackage = intent.getPackage();
            if (targetPackage == null) {
                ComponentName cn = intent.getComponent();
                if (cn != null) {
                    targetPackage = cn.getPackageName();
                }
            }
            return shouldHideFacebookApp(targetPackage);
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isFacebookAppHiddenFromCurrentApp() {
        try {
            String callerPackage = BActivityThread.getAppPackageName();
            if (callerPackage == null
                    || PKG_FACEBOOK_KATANA.equals(callerPackage)
                    || callerPackage.equals(BlackBoxCore.getHostPkg())) {
                return false;
            }
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ===== DevxyzClone fix (round 4): Meta packages/authorities + Custom Tabs =====
    // Round 2 hid Katana from package-info queries, but the audit found the SDK
    // could still (a) resolve the real Katana PlatformProvider, (b) reach Katana
    // through unhooked AIDL surfaces (queryIntentServices, getPackageInfoVersioned,
    // checkSignatures, resolveService fallthrough), and (c) pick the Chrome Custom
    // Tab login handler, whose OAuth redirect (fb<appid>://...) can never return
    // into a clone. Round 4 closes those gates so the SDK's only remaining
    // handler is its in-app WebView dialog - the flow that works in Dual Space.
    private static final String PKG_FACEBOOK_ORCA = "com.facebook.orca";
    private static final String PKG_FACEBOOK_WAKIZASHI = "com.facebook.wakizashi";
    private static final String ACTION_CUSTOM_TABS_SERVICE = "android.support.customtabs.action.CustomTabsService";

    private static boolean isMetaPackage(String pkg) {
        return PKG_FACEBOOK_KATANA.equals(pkg) || PKG_FACEBOOK_ORCA.equals(pkg)
                || PKG_FACEBOOK_WAKIZASHI.equals(pkg);
    }

    private static boolean isMetaProviderAuthority(String authority) {
        return authority != null && (authority.startsWith(PKG_FACEBOOK_KATANA)
                || authority.startsWith(PKG_FACEBOOK_ORCA)
                || authority.startsWith(PKG_FACEBOOK_WAKIZASHI));
    }

    // DevxyzClone fix (round 7): is this intent a PackageManager probe for a
    // Facebook Login redirect URI (fb<appid>://authorize or
    // fbconnect://cct.<package>)? The SDK's
    // Validate.hasCustomTabRedirectActivity runs exactly this query to
    // decide whether its Custom Tab handler may run.
    private static boolean isFacebookRedirectQuery(Intent intent) {
        if (intent == null || !isFacebookAppHiddenFromCurrentApp()) {
            return false;
        }
        try {
            android.net.Uri data = intent.getData();
            if (data == null) {
                return false;
            }
            String scheme = data.getScheme();
            if (scheme == null) {
                return false;
            }
            if (scheme.length() > 2 && scheme.charAt(0) == 'f'
                    && scheme.charAt(1) == 'b' && Character.isDigit(scheme.charAt(2))) {
                return true;
            }
            if ("fbconnect".equals(scheme)) {
                String host = data.getHost();
                return host != null && host.startsWith("cct.");
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isCustomTabsServiceIntent(Intent intent) {
        return intent != null && ACTION_CUSTOM_TABS_SERVICE.equals(intent.getAction());
    }

    private static boolean shouldHideFacebookService(Intent intent) {
        if (intent == null) {
            return false;
        }
        if (shouldHideFacebookApp(intent)) {
            return true;
        }
        try {
            String act = intent.getAction();
            return act != null && (act.startsWith("com.facebook.katana.")
                    || act.startsWith("com.facebook.platform."))
                    && isFacebookAppHiddenFromCurrentApp();
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static List<ResolveInfo> filterOutFacebookServices(List<ResolveInfo> resolves) {
        if (resolves == null || resolves.isEmpty()) {
            return resolves;
        }
        ArrayList<ResolveInfo> filtered = new ArrayList<>(resolves.size());
        for (ResolveInfo ri : resolves) {
            if (ri == null) {
                continue;
            }
            ServiceInfo si = ri.serviceInfo;
            if (si != null && PKG_FACEBOOK_KATANA.equals(si.packageName)) {
                continue;
            }
            filtered.add(ri);
        }
        return filtered;
    }

    private static List<ResolveInfo> filterOutFacebookActivities(List<ResolveInfo> resolves) {
        if (resolves == null || resolves.isEmpty()) {
            return resolves;
        }
        ArrayList<ResolveInfo> filtered = new ArrayList<>(resolves.size());
        for (ResolveInfo ri : resolves) {
            if (ri == null) {
                continue;
            }
            ActivityInfo ai = ri.activityInfo;
            if (ai != null && PKG_FACEBOOK_KATANA.equals(ai.packageName)) {
                continue;
            }
            filtered.add(ri);
        }
        return filtered;
    }

    // ===== DevxyzClone diagnostics (round 3, temporary) =====
    // Appends one line per relevant PackageManager event inside cloned apps to
    // Download/devxyzclone-pmdiag.log (and the app's external files dir) so the
    // exact identity answers given to SDKs can be inspected after a phone test.
    private static final Object DIAG_LOCK = new Object();

    private static void diagLog(String msg) {
        // Round 5: delegate to the shared DiagLog sink (host internal files
        // dir first) so the log survives scoped storage and is readable from
        // the in-app Diagnostic Log viewer.
        DiagLog.log(msg);
    }

    private static String diagKeyHash(android.content.pm.Signature[] sigs) {
        if (sigs == null || sigs.length == 0) {
            return "none";
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(sigs[0].toByteArray());
            return android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP);
        } catch (Throwable t) {
            return "err";
        }
    }

    private static String diagSigningInfoHash(android.content.pm.SigningInfo si) {
        if (si == null) {
            return "none";
        }
        try {
            return diagKeyHash(si.getApkContentsSigners());
        } catch (Throwable t) {
            return "err";
        }
    }

    private static boolean diagIsFacebookIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        try {
            StringBuilder sb = new StringBuilder();
            if (intent.getPackage() != null) {
                sb.append(intent.getPackage()).append(' ');
            }
            if (intent.getComponent() != null) {
                sb.append(intent.getComponent().flattenToShortString()).append(' ');
            }
            if (intent.getAction() != null) {
                sb.append(intent.getAction()).append(' ');
            }
            if (intent.getDataString() != null) {
                sb.append(intent.getDataString());
            }
            String s = sb.toString().toLowerCase();
            return s.contains("facebook") || s.contains("katana") || s.contains("customtab");
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String diagIntentSummary(Intent intent) {
        try {
            return "pkg=" + intent.getPackage()
                    + " cmp=" + (intent.getComponent() != null ? intent.getComponent().flattenToShortString() : null)
                    + " act=" + intent.getAction()
                    + " data=" + intent.getDataString();
        } catch (Throwable t) {
            return "err";
        }
    }

    public IPackageManagerProxy() {
        super(BRActivityThread.get().sPackageManager().asBinder());
    }

    @Override
    protected Object getWho() {
        return BRActivityThread.get().sPackageManager();
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        BRActivityThread.get()._set_sPackageManager(proxyInvocation);
        replaceSystemService("package");
        Object systemContext = BRActivityThread.get(BlackBoxCore.mainThread()).getSystemContext();
        BRContextImpl.get(systemContext).getPackageManager();
        PackageManager packageManager = BRContextImpl.get(systemContext).mPackageManager();
        if (packageManager != null) {
            BRPackageManager.get().disableApplicationInfoCache();
            try {
                Reflector.on("android.app.ApplicationPackageManager")
                        .field("mPM")
                        .set(packageManager, proxyInvocation);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("addOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("removeOnPermissionsChangeListener", 0));
        addMethodHook(new SimpleAudioPermissionHook());
        addMethodHook(new CheckSelfPermission());
        addMethodHook(new ShouldShowRequestPermissionRationale());
        addMethodHook(new RequestPermissions());
        addMethodHook(new DisableIconLoading());
        addMethodHook(new SetSplashScreenTheme());
        addMethodHook(new XiaomiSecurityBypass());
    }

    @ProxyMethod("resolveIntent")
    public static class ResolveIntent extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[0];

            // Privacy-first Samsung Health behavior: when host Samsung Account fallback is OFF,
            // ensure SaSDK cannot resolve intents into the host Samsung Account app.
            if (shouldHideHostSamsungAccountForSamsungHealth(intent)) {
                return null;
            }

            // DevxyzClone fix (round 2): intents aimed at the real Facebook app
            // must not resolve for clones.
            if (shouldHideFacebookApp(intent)) {
                diagLog("resolveIntent caller=" + BlackBoxCore.getAppPackageName()
                        + " " + diagIntentSummary(intent) + " -> null (katana hidden)");
                return null;
            }

            // Round 7: same redirect-URI hiding as QueryIntentActivities.
            if (isFacebookRedirectQuery(intent)) {
                diagLog("resolveIntent caller=" + BlackBoxCore.getAppPackageName()
                        + " " + diagIntentSummary(intent)
                        + " -> null (FB redirect URI hidden: forces WebView login)");
                return null;
            }

            String resolvedType = (String) args[1];
            int flags = MethodParameterUtils.toInt(args[2]);
            ResolveInfo resolveInfo = BlackBoxCore.getBPackageManager().resolveIntent(intent, resolvedType, flags, BlackBoxCore.getUserId());
            if (resolveInfo != null) {
                if (diagIsFacebookIntent(intent)) {
                    diagLog("resolveIntent caller=" + BlackBoxCore.getAppPackageName()
                            + " " + diagIntentSummary(intent) + " -> virtual "
                            + (resolveInfo.activityInfo != null ? resolveInfo.activityInfo.packageName : null));
                }
                return resolveInfo;
            }
            MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
            Object hostOut = method.invoke(who, args);
            // Implicit intents can still land on Katana through the host result;
            // suppress that too so the SDK takes the web flow.
            if (hostOut instanceof ResolveInfo && isFacebookAppHiddenFromCurrentApp()) {
                ResolveInfo hostRi = (ResolveInfo) hostOut;
                if (hostRi.activityInfo != null
                        && PKG_FACEBOOK_KATANA.equals(hostRi.activityInfo.packageName)) {
                    diagLog("resolveIntent caller=" + BlackBoxCore.getAppPackageName()
                            + " " + diagIntentSummary(intent) + " -> null (katana host result suppressed)");
                    return null;
                }
            }
            if (diagIsFacebookIntent(intent)) {
                diagLog("resolveIntent caller=" + BlackBoxCore.getAppPackageName()
                        + " " + diagIntentSummary(intent) + " -> "
                        + (hostOut instanceof ResolveInfo && ((ResolveInfo) hostOut).activityInfo != null
                            ? ((ResolveInfo) hostOut).activityInfo.packageName : String.valueOf(hostOut)));
            }
            return hostOut;
        }
    }

    @ProxyMethod("resolveService")
    public static class ResolveService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[0];
            String resolvedType = (String) args[1];
            int flags = MethodParameterUtils.toInt(args[2]);
            // Round 4: Katana services stay hidden, and Custom Tabs must look
            // unsupported inside clones (its OAuth redirect cannot return here).
            if (shouldHideFacebookService(intent)) {
                diagLog("resolveService caller=" + BlackBoxCore.getAppPackageName()
                        + " " + diagIntentSummary(intent) + " -> null (katana hidden)");
                return null;
            }
            if (isCustomTabsServiceIntent(intent) && isFacebookAppHiddenFromCurrentApp()) {
                diagLog("resolveService caller=" + BlackBoxCore.getAppPackageName()
                        + " " + diagIntentSummary(intent) + " -> null (custom tabs disabled in clones)");
                return null;
            }
            ResolveInfo resolveInfo = BlackBoxCore.getBPackageManager().resolveService(intent, flags, resolvedType, BlackBoxCore.getUserId());
            if (resolveInfo != null) {
                return resolveInfo;
            }
            MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
            Object hostOut = method.invoke(who, args);
            if (hostOut instanceof ResolveInfo && isFacebookAppHiddenFromCurrentApp()) {
                ResolveInfo hostRi = (ResolveInfo) hostOut;
                if (hostRi.serviceInfo != null
                        && PKG_FACEBOOK_KATANA.equals(hostRi.serviceInfo.packageName)) {
                    diagLog("resolveService caller=" + BlackBoxCore.getAppPackageName()
                            + " " + diagIntentSummary(intent) + " -> null (katana host result suppressed)");
                    return null;
                }
            }
            return hostOut;
        }
    }

    @ProxyMethod("queryIntentServices")
    public static class QueryIntentServices extends MethodHook {
        @Override
        @SuppressWarnings("unchecked")
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = MethodParameterUtils.getFirstParam(args, Intent.class);
            if (intent != null && isFacebookAppHiddenFromCurrentApp()
                    && (shouldHideFacebookService(intent) || isCustomTabsServiceIntent(intent))) {
                diagLog("queryIntentServices caller=" + BlackBoxCore.getAppPackageName()
                        + " " + diagIntentSummary(intent) + " -> [] (hidden in clones)");
                if (BuildCompat.isN()) {
                    return ParceledListSliceCompat.create(new ArrayList<ResolveInfo>());
                }
                return new ArrayList<ResolveInfo>();
            }
            MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
            Object hostOut = method.invoke(who, args);
            if (!isFacebookAppHiddenFromCurrentApp()) {
                return hostOut;
            }
            List<ResolveInfo> hostList = null;
            if (hostOut instanceof List) {
                hostList = (List<ResolveInfo>) hostOut;
            } else if (ParceledListSliceCompat.isParceledListSlice(hostOut)) {
                try {
                    hostList = Reflector.with(hostOut).method("getList").call();
                } catch (Throwable ignored) {
                }
            }
            if (hostList == null) {
                return hostOut;
            }
            hostList = filterOutFacebookServices(hostList);
            if (BuildCompat.isN()) {
                return ParceledListSliceCompat.create(hostList);
            }
            return hostList;
        }
    }

    @ProxyMethod("getPackageInfoVersioned")
    public static class GetPackageInfoVersioned extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            android.content.pm.VersionedPackage vp =
                    (android.content.pm.VersionedPackage) args[0];
            String packageName = vp != null ? vp.getPackageName() : null;
            int flags = MethodParameterUtils.toInt(args[1]);
            if (packageName == null) {
                return method.invoke(who, args);
            }
            // Same hiding/substitution as getPackageInfo - this AIDL entry point
            // otherwise bypasses rounds 1-2 entirely with real signatures.
            if (shouldHideFacebookApp(packageName)) {
                diagLog("getPackageInfoVersioned caller=" + BlackBoxCore.getAppPackageName()
                        + " q=" + packageName + " -> null (katana hidden)");
                return null;
            }
            PackageInfo packageInfo = BlackBoxCore.getBPackageManager()
                    .getPackageInfo(packageName, flags, BlackBoxCore.getUserId());
            if (packageInfo != null) {
                if (packageName.equals(BlackBoxCore.getHostPkg())
                        && (flags & (0x00000040 | 0x08000000)) != 0) {
                    try {
                        String cur = BlackBoxCore.getAppPackageName();
                        String host = BlackBoxCore.getHostPkg();
                        if (cur != null && host != null && !host.equals(cur)) {
                            PackageInfo own = BlackBoxCore.getBPackageManager()
                                    .getPackageInfo(cur, flags, BlackBoxCore.getUserId());
                            if (own != null) {
                                if (own.signingInfo != null) {
                                    packageInfo.signingInfo = own.signingInfo;
                                }
                                if (own.signatures != null && own.signatures.length > 0) {
                                    packageInfo.signatures = own.signatures;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return packageInfo;
            }
            if (AppSystemEnv.isOpenPackage(packageName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("checkSignatures")
    public static class CheckSignatures extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String p1 = (String) args[0];
            String p2 = (String) args[1];
            if (shouldHideFacebookApp(p1) || shouldHideFacebookApp(p2)) {
                diagLog("checkSignatures caller=" + BlackBoxCore.getAppPackageName()
                        + " " + p1 + " vs " + p2 + " -> NO_MATCH (katana hidden)");
                return PackageManager.SIGNATURE_NO_MATCH;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getInstallSourceInfo")
    public static class GetInstallSourceInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = args != null && args.length > 0 ? (String) args[0] : null;
            if (packageName == null) {
                return method.invoke(who, args);
            }

            int userId = BlackBoxCore.getUserId();
            if (BlackBoxCore.get().isInstalled(packageName, userId)) {
                Object info = createFakeInstallSourceInfo();
                if (info != null) {
                    return info;
                }
                // Last resort: behave like "not installed" rather than crashing the caller with NPE.
                throw new PackageManager.NameNotFoundException(packageName);
            }

            if (AppSystemEnv.isOpenPackage(packageName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }

            throw new PackageManager.NameNotFoundException(packageName);
        }

        private Object createFakeInstallSourceInfo() {
            // Privacy-first: never leak host installer / store identity. Return an object with null fields.
            try {
                Class<?> cls = Class.forName("android.content.pm.InstallSourceInfo");
                Constructor<?>[] ctors = cls.getDeclaredConstructors();
                if (ctors == null || ctors.length == 0) {
                    return null;
                }
                for (Constructor<?> ctor : ctors) {
                    try {
                        ctor.setAccessible(true);
                        Class<?>[] types = ctor.getParameterTypes();
                        Object[] params = new Object[types.length];
                        for (int i = 0; i < types.length; i++) {
                            Class<?> t = types[i];
                            if (t == String.class) {
                                params[i] = null;
                            } else if (t == int.class || t == Integer.class) {
                                params[i] = 0;
                            } else if (t == boolean.class || t == Boolean.class) {
                                params[i] = false;
                            } else {
                                // SigningInfo / other parcelables: null
                                params[i] = null;
                            }
                        }
                        return ctor.newInstance(params);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    @ProxyMethod("setComponentEnabledSetting")
    public static class SetComponentEnabledSetting extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            String packageName = componentName != null ? componentName.getPackageName() : null;
            if (packageName != null && BlackBoxCore.get().isInstalled(packageName, BlackBoxCore.getUserId())) {
                return 0;
            }
            if (packageName != null && AppSystemEnv.isOpenPackage(packageName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, 3);
                return method.invoke(who, args);
            }
            return 0;
        }
    }

    @ProxyMethod("setApplicationEnabledSetting")
    public static class SetApplicationEnabledSetting extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];
            if (packageName != null && BlackBoxCore.get().isInstalled(packageName, BlackBoxCore.getUserId())) {
                return null;
            }
            if (packageName != null && AppSystemEnv.isOpenPackage(packageName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, 3);
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getApplicationEnabledSetting")
    public static class GetApplicationEnabledSetting extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];
            if (packageName != null && BlackBoxCore.get().isInstalled(packageName, BlackBoxCore.getUserId())) {
                return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            }
            if (packageName != null && AppSystemEnv.isOpenPackage(packageName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }
            return PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        }
    }

    @ProxyMethod("getComponentEnabledSetting")
    public static class GetComponentEnabledSetting extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            String packageName = componentName != null ? componentName.getPackageName() : null;
            if (packageName != null && BlackBoxCore.get().isInstalled(packageName, BlackBoxCore.getUserId())) {
                return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            }
            if (packageName != null && AppSystemEnv.isOpenPackage(packageName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }
            return PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        }
    }

    @ProxyMethod("getPackageInfo")
    public static class GetPackageInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);

            // Samsung Health SaSDK should not detect the host Samsung Account package unless the
            // explicit per-app fallback toggle is enabled.
            if (shouldHideHostSamsungAccountForSamsungHealth(packageName)) {
                return null;
            }

            // DevxyzClone fix (round 2): the real Facebook app is invisible to clones.
            if (shouldHideFacebookApp(packageName)) {
                return null;
            }
            
            
            if ("com.android.vending".equals(packageName)) {
                PackageInfo fake = createFakeGooglePlayServicesPackageInfo();
                // Privacy-first (OG): by default keep Play Store fully fake.
                // If needed for compatibility, allow an explicit opt-in fallback.
                if (shouldAttachSigningInfo(flags) && isHostSigningFallbackEnabled()) {
                    PackageInfo real = tryGetRealHostPackageInfo(who, method, args);
                    if (real != null) {
                        attachSigningInfo(fake, real);
                    }
                }
                return fake;
            }
            
            PackageInfo packageInfo = BlackBoxCore.getBPackageManager().getPackageInfo(packageName, flags, BlackBoxCore.getUserId());
            if (packageInfo != null) {
                // DevxyzClone fix: a virtual app that asks for the HOST package's info is
                // almost always asking about ITSELF through the proxy identity (its
                // framework package name is the host's). SDKs such as Facebook Login
                // compute the app's key hash from these signatures; returning the
                // host's real signatures makes the SDK present the host's identity
                // and the login is rejected ("Invalid key hash"). Substitute the
                // current virtual app's own signatures so the app presents its
                // genuine signing identity, exactly as on a real device.
                if (packageName != null && packageName.equals(BlackBoxCore.getHostPkg())
                        && shouldAttachSigningInfo(flags)) {
                    replaceSignaturesWithCurrentApp(packageInfo, flags);
                }
                
                if (packageInfo.requestedPermissions != null && packageInfo.requestedPermissionsFlags != null) {
                    for (int i = 0; i < packageInfo.requestedPermissions.length; i++) {
                        String perm = packageInfo.requestedPermissions[i];
                        if (perm != null && (perm.equals(android.Manifest.permission.RECORD_AUDIO)
                                || perm.equals("android.permission.FOREGROUND_SERVICE_MICROPHONE")
                                || perm.equals(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
                                || perm.equals(android.Manifest.permission.CAPTURE_AUDIO_OUTPUT))) {
                            packageInfo.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_GRANTED;
                        }
                    }
                }
                if (shouldAttachSigningInfo(flags) || packageName.equals(BlackBoxCore.getHostPkg())
                        || PKG_FACEBOOK_KATANA.equals(packageName)) {
                    diagLog("getPackageInfo caller=" + BlackBoxCore.getAppPackageName()
                            + " q=" + packageName + " flags=" + Integer.toHexString(flags)
                            + " src=virtual sigHash=" + diagKeyHash(packageInfo.signatures)
                            + " signingInfoHash=" + diagSigningInfoHash(packageInfo.signingInfo));
                }
                return packageInfo;
            }
            if (AppSystemEnv.isOpenPackage(packageName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                Object result = method.invoke(who, args);
                // Same self-identity fix for the passthrough path: never let the
                // host's real signatures reach a virtual app asking about itself.
                if (result instanceof PackageInfo && packageName != null
                        && packageName.equals(BlackBoxCore.getHostPkg())
                        && shouldAttachSigningInfo(flags)) {
                    replaceSignaturesWithCurrentApp((PackageInfo) result, flags);
                }
                if (result instanceof PackageInfo && (shouldAttachSigningInfo(flags)
                        || packageName.equals(BlackBoxCore.getHostPkg())
                        || PKG_FACEBOOK_KATANA.equals(packageName))) {
                    PackageInfo rpi = (PackageInfo) result;
                    diagLog("getPackageInfo caller=" + BlackBoxCore.getAppPackageName()
                            + " q=" + packageName + " flags=" + Integer.toHexString(flags)
                            + " src=passthrough sigHash=" + diagKeyHash(rpi.signatures)
                            + " signingInfoHash=" + diagSigningInfoHash(rpi.signingInfo));
                }
                return result;
            }
            if (PKG_FACEBOOK_KATANA.equals(packageName) || packageName.equals(BlackBoxCore.getHostPkg())) {
                diagLog("getPackageInfo caller=" + BlackBoxCore.getAppPackageName()
                        + " q=" + packageName + " flags=" + Integer.toHexString(flags)
                        + " result=null");
            }
            return null;
        }

        private boolean shouldAttachSigningInfo(int flags) {
            // GET_SIGNATURES is deprecated but still used by some libs.
            final int GET_SIGNATURES = 0x00000040;
            // GET_SIGNING_CERTIFICATES replaces GET_SIGNATURES on P+.
            final int GET_SIGNING_CERTIFICATES = 0x08000000;
            return (flags & (GET_SIGNATURES | GET_SIGNING_CERTIFICATES)) != 0;
        }

        private boolean isHostSigningFallbackEnabled() {
            try {
                Context context = BlackBoxCore.getContext();
                if (context == null) {
                    return false;
                }
                String prefsName = context.getPackageName() + "_preferences";
                return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                        .getBoolean("host_signing_fallback", false);
            } catch (Throwable ignored) {
            }
            return false;
        }

        private PackageInfo tryGetRealHostPackageInfo(Object who, Method method, Object[] args) {
            try {
                Object[] argsCopy = args != null ? args.clone() : null;
                if (argsCopy == null) {
                    return null;
                }
                MethodParameterUtils.replaceUserIdIfNeeded(argsCopy, argsCopy.length - 1);
                Object out = method.invoke(who, argsCopy);
                return (out instanceof PackageInfo) ? (PackageInfo) out : null;
            } catch (Throwable ignored) {
            }
            return null;
        }

        private void attachSigningInfo(PackageInfo target, PackageInfo source) {
            if (target == null || source == null) {
                return;
            }
            try {
                if (source.signingInfo != null) {
                    target.signingInfo = source.signingInfo;
                }
            } catch (Throwable ignored) {
            }
            try {
                if (source.signatures != null && source.signatures.length > 0) {
                    target.signatures = source.signatures;
                }
            } catch (Throwable ignored) {
            }
        }

        // DevxyzClone fix: replace a PackageInfo's signatures with the current
        // virtual app's own signatures (see the call site in hook() above).
        // No-op in the host process itself, where the "current app" IS the host.
        private void replaceSignaturesWithCurrentApp(PackageInfo target, int flags) {
            if (target == null) {
                return;
            }
            try {
                String hostPkg = BlackBoxCore.getHostPkg();
                String currentPkg = BlackBoxCore.getAppPackageName();
                if (hostPkg == null || currentPkg == null || hostPkg.equals(currentPkg)) {
                    return;
                }
                PackageInfo own = BlackBoxCore.getBPackageManager()
                        .getPackageInfo(currentPkg, flags, BlackBoxCore.getUserId());
                if (own == null) {
                    return;
                }
                attachSigningInfo(target, own);
            } catch (Throwable ignored) {
            }
        }
        
        private PackageInfo createFakeGooglePlayServicesPackageInfo() {
            PackageInfo packageInfo = new PackageInfo();
            packageInfo.packageName = "com.android.vending";
            packageInfo.versionName = "33.8.16-21";
            packageInfo.versionCode = 83381621;
            
            ApplicationInfo appInfo = new ApplicationInfo();
            appInfo.packageName = "com.android.vending";
            appInfo.name = "Google Play Store";
            appInfo.flags = ApplicationInfo.FLAG_SYSTEM;
            appInfo.uid = 10001; 
            packageInfo.applicationInfo = appInfo;
            
            Slog.d(TAG, "GetPackageInfo: Providing fake Google Play Services info");
            return packageInfo;
        }
    }

    @ProxyMethod("getPackageUid")
    public static class GetPackageUid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getProviderInfo")
    public static class GetProviderInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            // Round 4: Meta provider components stay hidden from clones.
            if (componentName != null && shouldHideFacebookApp(componentName.getPackageName())) {
                diagLog("getProviderInfo caller=" + BlackBoxCore.getAppPackageName()
                        + " cmp=" + componentName.flattenToShortString() + " -> null (katana hidden)");
                return null;
            }
            ProviderInfo providerInfo = BlackBoxCore.getBPackageManager().getProviderInfo(componentName, flags, BlackBoxCore.getUserId());
            if (providerInfo != null)
                return providerInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getReceiverInfo")
    public static class GetReceiverInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ActivityInfo receiverInfo = BlackBoxCore.getBPackageManager().getReceiverInfo(componentName, flags, BlackBoxCore.getUserId());
            if (receiverInfo != null)
                return receiverInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getActivityInfo")
    public static class GetActivityInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ActivityInfo activityInfo = BlackBoxCore.getBPackageManager().getActivityInfo(componentName, flags, BlackBoxCore.getUserId());
            if (activityInfo != null)
                return activityInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getServiceInfo")
    public static class GetServiceInfo extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ServiceInfo serviceInfo = BlackBoxCore.getBPackageManager().getServiceInfo(componentName, flags, BlackBoxCore.getUserId());
            if (serviceInfo != null)
                return serviceInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getInstalledApplications")
    public static class GetInstalledApplications extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int flags = MethodParameterUtils.toInt(args[0]);
            List<ApplicationInfo> installedApplications = BlackBoxCore.getBPackageManager().getInstalledApplications(flags, BlackBoxCore.getUserId());
            return ParceledListSliceCompat.create(installedApplications);
        }
    }

    @ProxyMethod("getInstalledPackages")
    public static class GetInstalledPackages extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int flags = MethodParameterUtils.toInt(args[0]);
            List<PackageInfo> installedPackages = BlackBoxCore.getBPackageManager().getInstalledPackages(flags, BlackBoxCore.getUserId());
            return ParceledListSliceCompat.create(installedPackages);
        }
    }

    @ProxyMethod("getApplicationInfo")
    public static class GetApplicationInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];

            int flags = MethodParameterUtils.toInt(args[1]);

            if (shouldHideHostSamsungAccountForSamsungHealth(packageName)) {
                return null;
            }

            // DevxyzClone fix (round 2): the real Facebook app is invisible to clones.
            if (shouldHideFacebookApp(packageName)) {
                return null;
            }

            if (PKG_FACEBOOK_KATANA.equals(packageName) || packageName.equals(BlackBoxCore.getHostPkg())
                    || packageName.equals(BlackBoxCore.getAppPackageName())) {
                diagLog("getApplicationInfo caller=" + BlackBoxCore.getAppPackageName()
                        + " q=" + packageName + " flags=" + Integer.toHexString(flags));
            }



            ApplicationInfo applicationInfo = BlackBoxCore.getBPackageManager().getApplicationInfo(packageName, flags, BlackBoxCore.getUserId());
            if (applicationInfo != null) {
                return applicationInfo;
            }
            if (AppSystemEnv.isOpenPackage(packageName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("queryContentProviders")
    public static class QueryContentProviders extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int flags = MethodParameterUtils.toInt(args[2]);
            List<ProviderInfo> providers = BlackBoxCore.getBPackageManager().
                    queryContentProviders(BlackBoxCore.getAppProcessName(), BlackBoxCore.getBUid(), flags, BlackBoxCore.getUserId());
            return ParceledListSliceCompat.create(providers);
        }
    }

    @ProxyMethod("queryIntentActivities")
    public static class QueryIntentActivities extends MethodHook {
        @Override
        @SuppressWarnings("unchecked")
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = MethodParameterUtils.getFirstParam(args, Intent.class);
            String type = MethodParameterUtils.getFirstParam(args, String.class);
            Integer flags = MethodParameterUtils.getFirstParam(args, Integer.class);

            // DevxyzClone fix (round 7): the Facebook SDK enables its
            // Custom Tab handler only when PackageManager resolves the
            // app's own CustomTabActivity - alone - for
            // fb<appid>://authorize or fbconnect://cct.<package>. Inside a
            // clone that redirect can never come back (the real system
            // hands it to the ORIGINAL app), so answer EMPTY here: the SDK
            // disables the Custom Tab handler and uses its in-app WebView
            // dialog instead - the web login form inside the clone.
            if (isFacebookRedirectQuery(intent)) {
                diagLog("queryIntentActivities caller=" + BlackBoxCore.getAppPackageName()
                        + " " + diagIntentSummary(intent)
                        + " -> [] (FB redirect URI hidden: forces WebView login)");
                if (BuildCompat.isN()) {
                    return ParceledListSliceCompat.create(new ArrayList<ResolveInfo>());
                }
                return new ArrayList<ResolveInfo>();
            }

            List<ResolveInfo> resolves = BlackBoxCore.getBPackageManager()
                    .queryIntentActivities(intent, flags, type, BActivityThread.getUserId());

            if (resolves != null && !resolves.isEmpty()) {
                if (shouldHideHostSamsungAccountForSamsungHealthCaller()) {
                    resolves = filterOutSamsungAccountActivities(resolves);
                }
                // DevxyzClone fix (round 2): strip the real Facebook app from results.
                if (isFacebookAppHiddenFromCurrentApp()) {
                    resolves = filterOutFacebookActivities(resolves);
                }
                if (diagIsFacebookIntent(intent)) {
                    StringBuilder pkgs = new StringBuilder();
                    for (ResolveInfo ri : resolves) {
                        if (ri != null && ri.activityInfo != null) {
                            pkgs.append(ri.activityInfo.packageName).append(',');
                        }
                    }
                    diagLog("queryIntentActivities caller=" + BlackBoxCore.getAppPackageName()
                            + " " + diagIntentSummary(intent) + " -> virtual [" + pkgs + "]");
                }
                if (BuildCompat.isN()) {
                    return ParceledListSliceCompat.create(resolves);
                }
                return resolves;
            }

            MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
            Object hostOut = method.invoke(who, args);

            boolean hideFacebook = isFacebookAppHiddenFromCurrentApp();
            if (!shouldHideHostSamsungAccountForSamsungHealthCaller() && !hideFacebook) {
                return hostOut;
            }

            List<ResolveInfo> hostList = null;
            if (hostOut instanceof List) {
                hostList = (List<ResolveInfo>) hostOut;
            } else if (ParceledListSliceCompat.isParceledListSlice(hostOut)) {
                try {
                    hostList = Reflector.with(hostOut).method("getList").call();
                } catch (Throwable ignored) {
                }
            }

            if (shouldHideHostSamsungAccountForSamsungHealthCaller()) {
                hostList = filterOutSamsungAccountActivities(hostList);
            }
            if (hideFacebook) {
                hostList = filterOutFacebookActivities(hostList);
            }
            if (diagIsFacebookIntent(intent)) {
                StringBuilder pkgs = new StringBuilder();
                if (hostList != null) {
                    for (ResolveInfo ri : hostList) {
                        if (ri != null && ri.activityInfo != null) {
                            pkgs.append(ri.activityInfo.packageName).append(',');
                        }
                    }
                }
                diagLog("queryIntentActivities caller=" + BlackBoxCore.getAppPackageName()
                        + " " + diagIntentSummary(intent) + " -> host [" + pkgs + "]");
            }
            if (hostList == null) {
                return hostOut;
            }
            if (BuildCompat.isN()) {
                return ParceledListSliceCompat.create(hostList);
            }
            return hostList;
        }
    }

    @ProxyMethod("queryIntentReceivers")
    public static class QueryBroadcastReceivers extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = MethodParameterUtils.getFirstParam(args, Intent.class);
            String type = MethodParameterUtils.getFirstParam(args, String.class);
            Integer flags = MethodParameterUtils.getFirstParam(args, Integer.class);
            List<ResolveInfo> resolves = BlackBoxCore.getBPackageManager().queryBroadcastReceivers(intent, flags, type, BActivityThread.getUserId());
            Slog.d(TAG, "queryIntentReceivers count=" + (resolves == null ? 0 : resolves.size()));

            
            if (BuildCompat.isN()) {
                return ParceledListSliceCompat.create(resolves);
            }

            
            return resolves;
        }
    }

    @ProxyMethod("resolveContentProvider")
    public static class ResolveContentProvider extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String authority = (String) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            if (BlackBoxMediaContract.PUBLIC_AUTHORITY.equals(authority)) {
                ProviderInfo providerInfo = BlackBoxCore.getPackageManager()
                        .resolveContentProvider(BlackBoxCore.getHostPkg() + ".blackbox.MediaProvider", flags);
                if (providerInfo != null) {
                    providerInfo = new ProviderInfo(providerInfo);
                    providerInfo.authority = BlackBoxMediaContract.PUBLIC_AUTHORITY;
                    return providerInfo;
                }
            }
            // Round 4: the real Katana PlatformProvider must not leak. The old
            // fallthrough returned the REAL ProviderInfo for every non-virtual
            // authority, which by itself tells SDKs the Facebook app exists.
            if (isMetaProviderAuthority(authority) && isFacebookAppHiddenFromCurrentApp()) {
                diagLog("resolveContentProvider caller=" + BlackBoxCore.getAppPackageName()
                        + " auth=" + authority + " -> null (meta provider hidden)");
                return null;
            }
            ProviderInfo providerInfo = BlackBoxCore.getBPackageManager().resolveContentProvider(authority, flags, BActivityThread.getUserId());
            if (providerInfo == null) {
                providerInfo = BlackBoxCore.getPackageManager().resolveContentProvider(authority, flags);
                if (providerInfo != null && isMetaPackage(providerInfo.packageName)
                        && isFacebookAppHiddenFromCurrentApp()) {
                    diagLog("resolveContentProvider caller=" + BlackBoxCore.getAppPackageName()
                            + " auth=" + authority + " -> null (meta provider suppressed)");
                    return null;
                }
                if (providerInfo != null && AppSystemEnv.isOpenPackage(providerInfo.packageName)) {
                    return providerInfo;
                }
                return method.invoke(who, args);
            }
            return providerInfo;
        }
    }

    @ProxyMethod("canRequestPackageInstalls")
    public static class CanRequestPackageInstalls extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getPackagesForUid")
    public static class GetPackagesForUid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int uid = (Integer) args[0];
            if (uid == BlackBoxCore.getHostUid()) {
                args[0] = BActivityThread.getBUid();
                uid = (int) args[0];
            }
            String[] packagesForUid = BlackBoxCore.getBPackageManager().getPackagesForUid(uid);
            Slog.d(TAG, args[0] + " , " + BActivityThread.getAppProcessName() + " GetPackagesForUid: " + Arrays.toString(packagesForUid));
            return packagesForUid;
        }
    }

    @ProxyMethod("getInstallerPackageName")
    public static class GetInstallerPackageName extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            return "com.android.vending";
        }
    }

    @ProxyMethod("getSharedLibraries")
    public static class GetSharedLibraries extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            return ParceledListSliceCompat.create(new ArrayList<>());
        }
    }

    @ProxyMethod("getComponentEnabledSetting")
    public static class getComponentEnabledSetting extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            String packageName = componentName.getPackageName();
            ApplicationInfo applicationInfo = BlackBoxCore.getBPackageManager().getApplicationInfo(packageName,0, BActivityThread.getUserId());





            if(applicationInfo != null){
                return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            }
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            throw new IllegalArgumentException();
        }
    }





    @ProxyMethod("checkPermission")
    public static class SimpleAudioPermissionHook extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String permission = (String) args[0];
            String packageName = (String) args[1];
            
            
            if (isAudioPermission(permission)) {
                Slog.d(TAG, "SimpleAudioPermissionHook: Granting audio permission: " + permission + " to " + packageName);
                return PackageManager.PERMISSION_GRANTED;
            }

            
            if (isStorageOrMediaPermission(permission)) {
                Slog.d(TAG, "SimpleAudioPermissionHook: Granting storage/media permission: " + permission + " to " + packageName);
                return PackageManager.PERMISSION_GRANTED;
            }
            
            
            if (isNotificationOrXiaomiPermission(permission)) {
                Slog.d(TAG, "SimpleAudioPermissionHook: Granting notification/Xiaomi permission: " + permission + " to " + packageName);
                return PackageManager.PERMISSION_GRANTED;
            }
            
            
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("checkSelfPermission")
    public static class CheckSelfPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String permission = (String) args[0];
            String packageName = (String) args[1];
            
            
            if (isAudioPermission(permission)) {
                Slog.d(TAG, "CheckSelfPermission: Granting audio permission: " + permission + " to " + packageName);
                return PackageManager.PERMISSION_GRANTED;
            }

            
            if (isStorageOrMediaPermission(permission)) {
                Slog.d(TAG, "CheckSelfPermission: Granting storage/media permission: " + permission + " to " + packageName);
                return PackageManager.PERMISSION_GRANTED;
            }
            
            
            if (isNotificationOrXiaomiPermission(permission)) {
                Slog.d(TAG, "CheckSelfPermission: Granting notification/Xiaomi permission: " + permission + " to " + packageName);
                return PackageManager.PERMISSION_GRANTED;
            }
            
            
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("shouldShowRequestPermissionRationale")
    public static class ShouldShowRequestPermissionRationale extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String permission = (String) args[0];
            String packageName = (String) args[1];
            
            
            if (isAudioPermission(permission)) {
                Slog.d(TAG, "ShouldShowRequestPermissionRationale: Not showing rationale for audio permission: " + permission);
                return false;
            }

            
            if (isStorageOrMediaPermission(permission)) {
                Slog.d(TAG, "ShouldShowRequestPermissionRationale: Not showing rationale for storage/media permission: " + permission);
                return false;
            }
            
            
            if (isNotificationOrXiaomiPermission(permission)) {
                Slog.d(TAG, "ShouldShowRequestPermissionRationale: Not showing rationale for notification/Xiaomi permission: " + permission);
                return false;
            }
            
            
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("requestPermissions")
    public static class RequestPermissions extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String[] permissions = (String[]) args[0];
            String packageName = (String) args[1];
            
            
            
            if (permissions != null) {
                Slog.d(TAG, "RequestPermissions: Allowing permission request flow for: " + java.util.Arrays.toString(permissions));
            }
            
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getDrawable")
    public static class DisableIconLoading extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "Blocking icon loading to prevent resource errors");
            return null; 
        }
    }

    
    private static boolean isStorageOrMediaPermission(String permission) {
        if (permission == null) return false;
        
        if (permission.equals(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                || permission.equals(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            return true;
        }
        
        if (permission.equals(android.Manifest.permission.READ_MEDIA_AUDIO)
                || permission.equals(android.Manifest.permission.READ_MEDIA_VIDEO)
                || permission.equals(android.Manifest.permission.READ_MEDIA_IMAGES)
                || permission.equals("android.permission.READ_MEDIA_VISUAL")
                || permission.equals("android.permission.READ_MEDIA_AURAL")
                || permission.equals(android.Manifest.permission.ACCESS_MEDIA_LOCATION)) {
            return true;
        }
        
        if (permission.equals("android.permission.READ_MEDIA_AUDIO_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_VIDEO_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_IMAGES_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_AURAL_USER_SELECTED")) {
            return true;
        }
        return false;
    }

    
    private static boolean isAudioPermission(String permission) {
        if (permission == null) return false;
        return permission.equals(android.Manifest.permission.RECORD_AUDIO)
                || permission.equals(android.Manifest.permission.CAPTURE_AUDIO_OUTPUT)
                || permission.equals(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
                || permission.equals("android.permission.FOREGROUND_SERVICE_MICROPHONE")
                || permission.equals("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION")
                || permission.equals("android.permission.FOREGROUND_SERVICE_CAMERA")
                || permission.equals("android.permission.FOREGROUND_SERVICE_LOCATION")
                || permission.equals("android.permission.FOREGROUND_SERVICE_HEALTH")
                || permission.equals("android.permission.FOREGROUND_SERVICE_DATA_SYNC")
                || permission.equals("android.permission.FOREGROUND_SERVICE_SPECIAL_USE")
                || permission.equals("android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED")
                || permission.equals("android.permission.FOREGROUND_SERVICE_PHONE_CALL")
                || permission.equals("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE");
    }
    
    
    private static boolean isNotificationOrXiaomiPermission(String permission) {
        if (permission == null) return false;
        
        
        if (permission.equals("android.permission.POST_NOTIFICATIONS")) {
            return true;
        }
        
        
        if (permission.equals("miui.permission.USE_INTERNAL_GENERAL_API") ||
            permission.equals("miui.permission.OPTIMIZE_POWER") ||
            permission.equals("miui.permission.RUN_IN_BACKGROUND") ||
            permission.equals("miui.permission.POST_NOTIFICATIONS") ||
            permission.equals("miui.permission.AUTO_START") ||
            permission.equals("miui.permission.BACKGROUND_POPUP_WINDOW") ||
            permission.equals("miui.permission.SHOW_WHEN_LOCKED") ||
            permission.equals("miui.permission.TURN_SCREEN_ON")) {
            return true;
        }
        
        return false;
    }

    @ProxyMethod("setSplashScreenTheme")
    public static class SetSplashScreenTheme extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            
            
            String packageName = args.length > 0 ? (String) args[0] : "unknown";
            Slog.d(TAG, "SetSplashScreenTheme: Bypassing UID check for package: " + packageName);
            
            
            boolean isXiaomi = BuildCompat.isMIUI() || 
                              Build.MANUFACTURER.toLowerCase().contains("xiaomi") ||
                              Build.BRAND.toLowerCase().contains("xiaomi") ||
                              Build.DISPLAY.toLowerCase().contains("hyperos");
                              
            if (isXiaomi) {
                Slog.d(TAG, "SetSplashScreenTheme: Detected Xiaomi/HyperOS, using enhanced bypass");
                
                return null;
            }
            
            
            try {
                return method.invoke(who, args);
            } catch (SecurityException e) {
                Slog.w(TAG, "SetSplashScreenTheme: SecurityException caught, bypassing: " + e.getMessage());
                return null;
            } catch (Exception e) {
                
                if (e.getCause() instanceof SecurityException) {
                    Slog.w(TAG, "SetSplashScreenTheme: SecurityException (wrapped) caught, bypassing: " + e.getCause().getMessage());
                    return null;
                }
                throw e; 
            }
        }
    }

    
    public static class XiaomiSecurityBypass extends MethodHook {
        private static final String[] XIAOMI_SECURITY_METHODS = {
            "setApplicationEnabledSetting",
            "setComponentEnabledSetting", 
            "setInstallLocation",
            "setInstallerPackageName",
            "setPackageStoppedState",
            "setSystemAppState",
            "setApplicationCategoryHint",
            "setApplicationHiddenSettingAsUser",
            "setBlockUninstallForUser",
            "setDefaultBrowserPackageNameAsUser",
            "setDistractingPackageRestrictionsAsUser",
            "setPackagesSuspendedAsUser",
            "setUpdateAvailable",
            "setRequiredForSystemUser",
            "setSystemAppHiddenUntilInstalled",
            "setHarmfulAppWarningEnabled",
            "setKeepUninstalledPackages",
            "verifyIntentFilter",
            "verifyPendingInstall",
            "extendVerificationTimeout",
            "setDefaultHomeActivity",
            "resetApplicationPreferences",
            "clearApplicationProfileData",
            "clearApplicationUserData",
            "deleteApplicationCacheFiles",
            "deleteApplicationCacheFilesAsUser",
            "freeStorageAndNotify",
            "freeStorage",
            "movePackage",
            "movePackageToSd",
            "movePrimaryStorage"
        };

        @Override
        public boolean isEnable() {
            
            return BuildCompat.isMIUI() || 
                   Build.MANUFACTURER.toLowerCase().contains("xiaomi") ||
                   Build.BRAND.toLowerCase().contains("xiaomi") ||
                   Build.DISPLAY.toLowerCase().contains("hyperos");
        }

        @Override
        public String getMethodName() {
            return null; 
        }

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            
            
            for (String securityMethod : XIAOMI_SECURITY_METHODS) {
                if (securityMethod.equals(methodName)) {
                    Slog.d(TAG, "XiaomiSecurityBypass: Intercepting " + methodName + " on Xiaomi/HyperOS");
                    
                    
                    if (method.getReturnType() == void.class) {
                        return null;
                    }
                    
                    else if (method.getReturnType() == boolean.class) {
                        return true;
                    }
                    
                    else if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    
                    else {
                        return null;
                    }
                }
            }
            
            
            try {
                return method.invoke(who, args);
            } catch (SecurityException e) {
                Slog.w(TAG, "XiaomiSecurityBypass: SecurityException in " + methodName + ", bypassing: " + e.getMessage());
                
                if (method.getReturnType() == boolean.class) {
                    return false;
                } else if (method.getReturnType() == int.class) {
                    return -1;
                } else {
                    return null;
                }
            } catch (Exception e) {
                if (e.getCause() instanceof SecurityException) {
                    Slog.w(TAG, "XiaomiSecurityBypass: SecurityException (wrapped) in " + methodName + ", bypassing: " + e.getCause().getMessage());
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    } else if (method.getReturnType() == int.class) {
                        return -1;
                    } else {
                        return null;
                    }
                }
                throw e;
            }
        }
    }
}
