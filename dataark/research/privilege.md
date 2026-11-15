# Privilege Integration Research — Root (libsu), Shizuku, Dhizuku

Status: verified research note for the DataArk backup app (`dev.dataark.backup`, minSdk 26, targetSdk 36).
No app source was modified; this file is the only artifact.
Verified against real artifacts (AAR/JAR bytecode, upstream sources, AOSP sources) and one attached device
(Android 11 emulator, userdebug, see §8). Everything below that is Android-13+-specific is marked as such.

Versions in scope:

| Component | Coordinate | Version | Local artifact inspected |
|---|---|---|---|
| Root | `com.github.topjohnwu.libsu:core` (JitPack) | 6.0.0 | `.scratch/libsu/jitcore.aar` + `core/src/.../Shell.java` |
| Shizuku | `dev.rikka.shizuku:api` + `:provider` | 13.1.5 | `.scratch/shizuku/x_api-13.1.5`, `.scratch/shizuku/x_provider-13.1.5`, `Shizuku.java`, `ShizukuProvider.java`, `README.md` |
| Dhizuku | `io.github.iamr0s:Dhizuku-API` | 2.7.0 | `.scratch/dhizuku/dhizuku.aar` (`out/`), `.scratch/dhizuku-src/Dhizuku-API-main` |
| Dhizuku server internals | `io.github.iamr0s:AndroidAppProcess` | 1.5.0 | `.scratch/appprocess/aap.aar` (transitive dep of Dhizuku server) |
| AOSP references | `frameworks/base` main, `system/core` main | — | `.scratch/aosp/ShellManifest.xml`, `PackageInstallerSession.java`, `PackageInstallerService.java`, `run-as.cpp` |

---

## 0. Verdict up front

1. **Root (libsu) is the only backend that can read another app's private `/data/data`** without the app being
   debuggable — because it gives uid 0 (`su`).
2. **Shizuku in ADB mode runs as uid 2000 (shell). It can NOT read `/data/data/<other app>`.** This is a hard
   Linux uid/SELinux boundary, not an Android permission that can be requested. It *can* read another app's APKs
   (world-readable), `/sdcard/Android/data` + `/sdcard/Android/obb` (shell uid), and drive `pm`/`cmd`
   (install/uninstall/clear/grant). For private data it only has `run-as` and only for **debuggable** apps.
3. **Shizuku in root mode (`Shizuku.getUid() == 0`) can read everything root can**, including `/data/data`.
4. **Dhizuku 2.7.0 `newProcess` runs as the Dhizuku *server app's own uid*** (an ordinary `untrusted_app`
   uid like `u0_aXXX`), **not** root and not shell. It can NOT read `/data/data`, and it can NOT `su` unless the
   Dhizuku app itself was granted root by the su manager (not part of Dhizuku, not the default). Dhizuku's real
   power is *device-owner identity*: silent package install/uninstall, permission grant state, `clearApplicationUserData`,
   delegated scopes, `remoteTransact`/`binderWrapper` to system services. Nothing filesystem-level.
5. **Critical API break:** `Shizuku.newProcess(...)` is **private in Shizuku API 13.1.1+** (public in 13.0.0 and
   13.1.0 only). In 13.1.5 you must use **`UserService`** (or pin 13.1.0, not recommended). This alone changes the
   integration design.
6. **Neither permission can be granted with `adb shell pm grant`.** Both managers keep their own allow-lists and
   ignore the Android permission bit: Shizuku checks its `ClientManager` record (`is not an attached client` error),
   Dhizuku checks a Room DB row keyed by uid **plus the caller's signing-certificate hash**. Use the in-app request
   dialogs (or the managers' UIs).

---

## 1. Manifest / dependency wiring (exact)

### 1.1 Gradle dependencies

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }     // libsu is only on JitPack
}

dependencies {
    // Root
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    // optional; only if running code in a root process (RootService / nio FileSystemManager)
    implementation("com.github.topjohnwu.libsu:service:6.0.0")

    // Shizuku: BOTH artifacts are needed
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")   // pulls api + provider + aidl/shared

    // Dhizuku
    implementation("io.github.iamr0s:Dhizuku-API:2.7.0")
}
```

maven-metadata (verified): `dev.rikka.shizuku:api:13.1.5` depends on `dev.rikka.shizuku:aidl:13.1.5` and
`dev.rikka.shizuku:shared:13.1.5`, plus `androidx.annotation:1.3.0`. `provider:13.1.5` depends on `api:13.1.5`.

**minSdk constraints (manifest merger):** Shizuku `api` AAR declares `minSdkVersion 24`; Shizuku `provider` and
Dhizuku AARs declare `23`; libsu declares `19`. DataArk is minSdk 26, so no override is needed. If minSdk were
< 24 the build would fail on the Shizuku api AAR.

### 1.2 Manifest block

```xml
<!-- app/src/main/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Shizuku: normally merged from the provider AAR (moe.shizuku.manager.permission.API_V23).
         Shown explicitly because it documents the contract; duplicate is harmless. -->
    <uses-permission android:name="moe.shizuku.manager.permission.API_V23" />

    <!-- Dhizuku: merged from the Dhizuku-API AAR. Shown explicitly for the same reason. -->
    <uses-permission android:name="com.rosan.dhizuku.permission.API" />

    <!-- Optional, ONLY for "is Shizuku / Dhizuku installed?" checks on Android 11+ (package visibility).
         Not needed to receive the binders. -->
    <queries>
        <package android:name="moe.shizuku.privileged.api" />
        <package android:name="com.rosan.dhizuku" />
    </queries>

    <application>
        <!-- REQUIRED for Shizuku. NOT shipped in the provider AAR: you must declare it yourself.
             Authority MUST be "${applicationId}.shizuku".
             multiprocess MUST be false; exported MUST be true; otherwise ShizukuProvider.attachInfo()
             throws IllegalStateException at runtime and the binder never arrives. -->
        <provider
            android:name="rikka.shizuku.ShizukuProvider"
            android:authorities="${applicationId}.shizuku"
            android:enabled="true"
            android:exported="true"
            android:multiprocess="false"
            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
    </application>
</manifest>
```

For DataArk that resolves to authority `dev.dataark.backup.shizuku` (debug suffix included automatically because
`${applicationId}` is used).

Notes, verified from the AARs:

* `provider` AAR manifest (`.scratch/shizuku/x_provider-13.1.5/AndroidManifest.xml`):
  `<uses-permission android:name="moe.shizuku.manager.permission.API_V23" />` and
  `<application><meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true" /></application>`.
  It declares **no `<provider>`** — that entry is app responsibility.
* Shizuku requires the provider to be exported/multiprocess=false and calls
  `Uri.parse("content://" + context.getPackageName() + ".shizuku")` when a non-provider process asks for the
  binder (`ShizukuProvider.requestBinderForNonProviderProcess`). A wrong authority = no binder, no error.
* `android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"` protects the provider from normal apps;
  the Shizuku manager (uid 2000/shell) holds it, your app does not need to hold it.
* Dhizuku AAR manifest (`dhizuku/out/AndroidManifest.xml`) is already complete and is merged automatically:
  `<uses-permission android:name="com.rosan.dhizuku.permission.API" />` plus a `<queries>` block containing
  `<package android:name="com.rosan.dhizuku" />`, `<provider android:authorities="com.rosan.dhizuku.server.provider" />`,
  and intent actions `com.rosan.dhizuku.server.provider` / `com.rosan.dhizuku.action.request.permission`.
  **No Dhizuku component, provider or activity has to be declared by DataArk.** Do not add
  `tools:node="remove"` on these or `init()`/`requestPermission()` will silently fail on Android 11+.
* Dhizuku permission in the Dhizuku app is `android:protectionLevel="dangerous"`
  (`.scratch/dhizuku/app-manifest.xml`), i.e. a *declared* custom dangerous permission — but see §2.3: it is not
  what actually authorizes you.

---

## 2. Exact APIs (Kotlin + Java signatures, errors, binder death)

### 2.1 libsu 6.0.0 — root shell

Installed artifact `jitcore.aar` (extracted in `.scratch/libsu/jx/classes.jar`) exposes:

```java
public static Shell getShell();                                  // blocking; may throw NoShellException
public static void  getShell(Shell.GetShellCallback cb);          // async, callback on main thread
public static void  getShell(Executor e, Shell.GetShellCallback cb);
public static Shell getCachedShell();                            // null if none / dead
public static Boolean isAppGrantedRoot();                        // true / false / null = undetermined
public static Shell.Job cmd(String... commands);                 // uses main shell
public static boolean rootAccess();
// instance
public abstract boolean isAlive();
public abstract int getStatus();                                  // UNKNOWN/NON_ROOT_SHELL/ROOT_SHELL
public boolean isRoot();                                          // getStatus() == ROOT_SHELL
public abstract Shell.Job newJob();
// Job
public abstract Shell.Job to(List<String> out);
public abstract Shell.Job to(List<String> out, List<String> err);
public abstract Shell.Job add(String... commands);
public abstract Shell.Job add(InputStream script);
public abstract Shell.Result exec();
public final void submit();  public final void submit(Shell.ResultCallback cb);
public abstract Future<Shell.Result> enqueue();
// Result
public abstract List<String> getOut();  public abstract List<String> getErr();
public abstract int getCode();           public boolean isSuccess();   // code == 0
// interface
public interface Shell.GetShellCallback { void onShell(Shell shell); }
```

Key behavioural facts (from `Shell.Builder.build()` javadoc, `Shell.java` upstream):

* Build order: `su --mount-master` (if `FLAG_MOUNT_MASTER`), then `su`, then **`sh` fallback**. The fallback
  **never fails**; it returns a shell running as *your own app uid* with `getStatus() == NON_ROOT_SHELL`.
  Therefore **always check `shell.isRoot` / `Shell.isAppGrantedRoot()`; never assume `getShell()` means root.**
* `getShell()` throws `NoShellException` if even `sh` cannot be created, or if the cached main shell died.
  `getShell(cb)` **does not call the callback** on `NoShellException` (it only logs) — a UI waiting on the
  callback can hang forever. Prefer the blocking call on a background thread with your own timeout, or combine
  async with a watchdog.
* `Shell.setDefaultBuilder(...)` throws `IllegalStateException("The main shell was already created")` if called
  after the main shell exists. Configure in `Application.onCreate()`/static init, before any `Shell.*` call.
* `getShell()` blocks on the su prompt; root prompt is shown once per install; `Shell.isAppGrantedRoot()` returns
  `false` after the user denies and remembers.

```kotlin
// Application.onCreate() — configuration must precede the first Shell call
init {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    Shell.setDefaultBuilder(
        Shell.Builder.create()
            .setFlags(Shell.FLAG_REDIRECT_STDERR)   // merge stderr into stderr list (or FLAG_MOUNT_MASTER)
            .setTimeout(20)                          // seconds to wait for shell verification; default 20
    )
}

enum class RootState { GRANTED, DENIED, UNKNOWN }

fun rootState(): RootState = when (Shell.isAppGrantedRoot()) {
    true  -> RootState.GRANTED
    false -> RootState.DENIED
    null  -> RootState.UNKNOWN
}

/** Runs a command with real root; returns null if root is not granted. Never call on the main thread. */
fun runAsRoot(vararg commands: String): Shell.Result? {
    if (Shell.isAppGrantedRoot() != true) return null
    return try {
        val shell = Shell.getShell()               // may block/prompt; may throw NoShellException
        check(shell.isRoot) { "libsu fell back to a non-root sh (app uid!)" }
        shell.newJob().add(*commands).exec()
    } catch (e: NoShellException) {
        null                                        // no usable shell at all
    }
}

// async variant (callback is main-thread; it is NOT called when NoShellException occurs)
Shell.getShell(Shell.GetShellCallback { shell ->
    if (shell.isRoot) { /* full mode */ } else { /* fallback sh = app uid, NOT shell 2000 */ }
})
```

Error/edge cases:

| Situation | Behaviour |
|---|---|
| No root / user denied | `Shell.isAppGrantedRoot() == false`, `Shell.getShell()` returns NON_ROOT_SHELL (app uid) → no extra privilege |
| Root prompt pending | `getShell()` blocks; use async + spinner |
| Cached shell died | `getCachedShell()` returns null and `getShell()` transparently rebuilds (a second su prompt may appear). `NoShellException("The main shell died during initialization")` is only thrown if a rebuild is already in flight |
| Command output | `Result.getOut()` is line-based **text**; binary output (tar) is not safe — write tar to a file or use `service`+`nio` (`FileSystemManager`) |
| `Result.getCode() == Shell.Result.JOB_NOT_EXECUTED` | job never ran (thrown earlier / shell unavailable) |

Binary streaming recommendation for DataArk: root shell runs
`tar -cf /sdcard/Android/data/dev.dataark.backup/files/<id>.tar -C /data/data/<pkg> .` (root can write into our
own external dir), then Kotlin streams that file; or use the `service` module's `FileSystemManager`/`RootService`
for fd-level I/O. Do **not** try to read `Result.getOut()` as bytes.

### 2.2 Shizuku 13.1.5

Public surface of `rikka.shizuku.Shizuku` (javap on `x_api-13.1.5/classes.jar`):

```java
public static IBinder getBinder();                     // null if not received
public static boolean pingBinder();
public static int  getUid();                            // 0 root, 2000 adb/shell, -1 if no permission (SecurityException caught)
public static int  getVersion();                        // Shizuku API version of server
public static boolean isPreV11();
public static String getSELinuxContext();
public static void addBinderReceivedListener(OnBinderReceivedListener);
public static void addBinderReceivedListener(OnBinderReceivedListener, Handler);
public static void addBinderReceivedListenerSticky(OnBinderReceivedListener);       // fires immediately if already received
public static void addBinderReceivedListenerSticky(OnBinderReceivedListener, Handler);
public static boolean removeBinderReceivedListener(OnBinderReceivedListener);
public static void addBinderDeadListener(OnBinderDeadListener);
public static boolean removeBinderDeadListener(OnBinderDeadListener);
public static void addRequestPermissionResultListener(OnRequestPermissionResultListener);
public static boolean removeRequestPermissionResultListener(OnRequestPermissionResultListener);
public static int  checkSelfPermission();               // PackageManager.PERMISSION_GRANTED/DENIED
public static boolean shouldShowRequestPermissionRationale();
public static void requestPermission(int requestCode);
public static int  checkRemotePermission(String permission);
public static void bindUserService(Shizuku.UserServiceArgs args, ServiceConnection conn);
public static void unbindUserService(Shizuku.UserServiceArgs args, ServiceConnection conn, boolean remove);
public static int  peekUserService(Shizuku.UserServiceArgs args, ServiceConnection conn);
public static void exit();
public static void transactRemote(Parcel data, Parcel reply, int flags);
public static void attachUserService(...); public static void dispatchPermissionConfirmationResult(...);
// private in 13.1.5 (was public in 13.0.0/13.1.0):
// private static ShizukuRemoteProcess newProcess(String[] cmd, String[] env, String dir)
```

Listener signatures (exact, Java):

```java
public interface Shizuku.OnBinderReceivedListener        { void onBinderReceived(); }
public interface Shizuku.OnBinderDeadListener            { void onBinderDead(); }
public interface Shizuku.OnRequestPermissionResultListener { void onRequestPermissionResult(int requestCode, int grantResult); }
// optional: all listeners accept an extra android.os.Handler as 2nd arg; default = main thread
```

`Shizuku.UserServiceArgs` (javap):

```java
public UserServiceArgs(ComponentName componentName);
public UserServiceArgs daemon(boolean);          // keep running after unbind
public UserServiceArgs tag(String);              // identity; defaults to class name (R8-unstable!)
public UserServiceArgs version(int);             // mismatch -> old service destroyed, new one started
public UserServiceArgs debuggable(boolean);
public UserServiceArgs processNameSuffix(String);
```

Initialization pattern (no `Shizuku.init()` exists — the `ShizukuProvider` delivers the binder; you only listen):

```kotlin
class DataArkApp : Application() {
    private val binderReceived = Shizuku.OnBinderReceivedListener {
        // Shizuku.getBinder() != null; safe to call Shizuku.* now
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        // Shizuku stopped/restarted: all Shizuku.* calls now throw IllegalStateException
        // mark state NOT_RUNNING, unbind/cancel jobs, re-arm listeners (sticky re-fires on new binder)
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) { /* enable privileged features */ }
        else { /* show rationale; denied */ }
    }

    override fun onCreate() {
        super.onCreate()
        // Multi-process app: in Application.onCreate() of EVERY process, tell ShizukuProvider whether this
        // process hosts the provider (normally true only for the main process), then let other processes pull
        // the binder. Skip both lines for a single-process app.
        // ShizukuProvider.enableMultiProcessSupport(applicationInfo.processName == packageName)
        // if (applicationInfo.processName != packageName) ShizukuProvider.requestBinderForNonProviderProcess(this)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }
}
```

Permission + uid:

```kotlin
fun shizukuPermissionGranted(): Boolean = try {
    Shizuku.pingBinder() && !Shizuku.isPreV11() &&
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
} catch (e: IllegalStateException) {   // binder hasn't been received
    false
}

fun requestShizukuPermission(requestCode: Int) {
    if (Shizuku.isPreV11()) return
    if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return
    if (Shizuku.shouldShowRequestPermissionRationale()) return  // "deny & don't ask again"
    Shizuku.requestPermission(requestCode)                      // result to OnRequestPermissionResultListener
}

fun privilege(): ShizukuPrivilege {
    val uid = Shizuku.getUid()      // -1 = permission not granted (no throw!)
    return when (uid) {
        0    -> ShizukuPrivilege.ROOT      // Sui/root start: uid 0
        2000 -> ShizukuPrivilege.ADB       // adb start: shell uid, NO /data/data access
        else -> ShizukuPrivilege.NONE
    }
}
```

**Running a shell command — what actually works on 13.1.5**

`Shizuku.newProcess` is private in 13.1.5, so the only supported way is a **UserService**: your class runs in a
process spawned by the Shizuku server with uid 0 (root) or 2000 (adb/shell). Minimum working setup:

```aidl
// app/src/main/aidl/dev/dataark/backup/ipc/IShellService.aidl
package dev.dataark.backup.ipc;

import android.os.ParcelFileDescriptor;

interface IShellService {
    // Reserved by Shizuku: AIDL code 16777114 => actual transaction 16777115. Must exist and System.exit().
    void destroy() = 16777114;

    String exec(String command) throws android.os.RemoteException;                 // text results
    ParcelFileDescriptor execStream(String command) throws android.os.RemoteException; // binary (tar)
}
```

```kotlin
// app/src/main/java/dev/dataark/backup/ipc/ShellUserService.kt
@Keep
class ShellUserService : IShellService.Stub() {
    constructor() : super()
    @Keep constructor(context: Context) : super()   // Shizuku v13 tries this constructor first

    override fun exec(command: String): String {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out                       // runs with Shizuku's uid (0 or 2000)
    }

    override fun execStream(command: String): ParcelFileDescriptor {
        val p = ProcessBuilder("sh", "-c", command).start()
        val pipe = ParcelFileDescriptor.createPipe()
        val write = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        Thread {
            p.inputStream.use { it.copyTo(write) }
            p.errorStream.use { it.copyTo(write) }
            write.close()
            p.waitFor()
        }.apply { isDaemon = true }.start()
        return pipe[0]
    }

    override fun destroy() { System.exit(0) }        // required: Shizuku does NOT kill the process on unbind
}
```

```kotlin
// client side
private val args = Shizuku.UserServiceArgs(
        ComponentName(context, ShellUserService::class.java))
    .daemon(false)
    .tag("dataark-shell")              // explicit tag: class name is unstable after R8
    .processNameSuffix("shell")
    .debuggable(BuildConfig.DEBUG)
    .version(BuildConfig.VERSION_CODE)

private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val svc = IShellService.Stub.asInterface(binder)
        // e.g. svc.exec("id; ls /data/data")
    }
    override fun onServiceDisconnected(name: ComponentName) {
        // service process died / Shizuku restarted
    }
}

fun bindShell() {
    if (Shizuku.getUid() == -1) return                        // no permission
    Shizuku.bindUserService(args, connection)                 // throws IllegalStateException/SecurityException
}

fun unbindShell() = Shizuku.unbindUserService(args, connection, true)
// then the service's destroy() runs (binder caller) and must System.exit(0)
```

Notes/limits:
* The UserService process is **not a valid Android app process**: `Context` is only a package context created by
  the server, `registerReceiver`/`getContentResolver` etc. do not work (README §UserService). It is fine for
  `Runtime.exec`/`ProcessBuilder` and `java.io`.
* File transfer back to the app: use an AIDL method returning `ParcelFileDescriptor` (as above) — **not** a
  `String`; Binder transactions are capped (~1 MB) and text is wrong for tar. Alternatively the service can write
  to a path the app can read (`/sdcard/Android/data/dev.dataark.backup/files/...`; shell can write there; root can
  write into app-private dir too). Do **not** hand off via `/data/local/tmp` — apps cannot read it.
* Keep the AIDL stub + service class from R8 (`@Keep`, consumer rules) or Shizuku throws
  `ClassNotFoundException`/`NoSuchMethodException` at bind time.
* If you cannot use UserService, the alternative is `ShizukuBinderWrapper` around a system-service binder
  (`SystemServiceHelper.getSystemService("package")` etc.) — Binder calls only, e.g. `IPackageManager`,
  `IPackageInstaller`. Not a shell.
* Legacy `newProcess` code only compiles against **13.0.0 or 13.1.0** (verified by javap: public there,
  private from 13.1.1). Pinned snippet if you must:
  ```kotlin
  // ONLY with dev.rikka.shizuku:api:13.0.0 or 13.1.0
  val p = Shizuku.newProcess(arrayOf("sh", "-c", "id"), null, null)
  val uidLine = p.inputStream.bufferedReader().readText()
  val exit = p.waitFor()
  ```
  Pinning is fragile (13.1.1+ servers are backward compatible, but the library on device is whatever the user
  installed); prefer UserService.

Error handling / binder death for Shizuku:

| Situation | Behaviour |
|---|---|
| Shizuku not installed / not started | binder never arrives; `getBinder()==null`, `pingBinder()==false`; calling `getUid()`/`requestPermission()`/`bindUserService()` throws `IllegalStateException("binder haven't been received")` |
| Binder died (Shizuku stopped, device reboot, force stop) | `addBinderDeadListener.onBinderDead()`; afterwards every privileged call throws `IllegalStateException` again until a new binder is received (`addBinderReceivedListenerSticky` re-arms) |
| Permission not granted | `checkSelfPermission()==DENIED`; `getUid()` returns `-1`; `requestPermission()` triggers Shizuku's dialog; privileged calls throw `SecurityException` on the server side ("is not an attached client") wrapped by the API as `RuntimeException`/`SecurityException` |
| Remote throw | API rethrows `RemoteException` as `RuntimeException` |
| UserService dies | `onServiceDisconnected`; `unbindUserService(..., true)` cleans the record; the process is **not** killed by Shizuku — your `destroy()` must `System.exit(0)` |
| Restart loops | listeners may fire multiple times; re-register on process start and keep state idempotent |

### 2.3 Dhizuku 2.7.0

Public surface (`dhizuku-api/src/main/java/com/rosan/dhizuku/api/Dhizuku.java`, identical to AAR bytecode):

```java
public static boolean init(Context context);            // false = not installed / not active / provider call failed
public static boolean init();                           // uses ActivityThread application
public static int getVersionCode();                     // Dhizuku server API protocol version (2.7.0 => 8)
public static String getVersionName();
public static String getOwnerPackageName();             // com.rosan.dhizuku when official
public static ComponentName getOwnerComponent();
public static boolean isPermissionGranted();            // server-side allow-list check
public static void requestPermission(DhizukuRequestPermissionListener listener);
public static DhizukuRemoteProcess newProcess(String[] cmd, String[] env, File dir);
public static boolean remoteTransact(IBinder binder, int code, Parcel data, Parcel reply, int flags);
public static IBinder binderWrapper(IBinder binder);    // transact system services as the Dhizuku server uid
public static void startUserService(DhizukuUserServiceArgs args);
public static void stopUserService(DhizukuUserServiceArgs args);
public static boolean bindUserService(DhizukuUserServiceArgs args, ServiceConnection conn);
public static boolean unbindUserService(ServiceConnection conn);
public static String[] getDelegatedScopes(); public static void setDelegatedScopes(String[] scopes);
public static void addLockTaskModeListener(DhizukuLockTaskModeListener listener);
public static void removeLockTaskModeListener(DhizukuLockTaskModeListener listener);
```

Listener signature (exact, Java — `throws RemoteException`):

```java
public interface DhizukuRequestPermissionListener { void onRequestPermission(int grantResult) throws RemoteException; }
public interface DhizukuLockTaskModeListener { /* separate, see API */ }
```

`DhizukuRemoteProcess extends java.lang.Process`: `getOutputStream()`, `getInputStream()`, `getErrorStream()`,
`exitValue()`, `waitFor()`, `waitFor(long, TimeUnit)`, `destroy()`, `isAlive()`.

Initialization + permission:

```kotlin
fun dhizukuReady(context: Context): Boolean {
    if (!Dhizuku.init(context)) return false           // Dhizuku not installed, or not device owner / disabled
    if (Dhizuku.isPermissionGranted()) return true
    Dhizuku.requestPermission(object : DhizukuRequestPermissionListener {
        override fun onRequestPermission(grantResult: Int) {
            // grantResult == PackageManager.PERMISSION_GRANTED / PERMISSION_DENIED
            // NOTE: API enforces a 15 s timeout and calls this with DENIED if the server does not answer;
            //       it also calls DENIED when Dhizuku's RequestPermissionActivity cannot be resolved.
        }
    })
    return false
}
```

Server uid (there is no `Dhizuku.getUid()`): `Dhizuku.getOwnerPackageName()` is the Dhizuku package, so

```kotlin
fun dhizukuServerUid(context: Context): Int? = runCatching {
    context.packageManager.getApplicationInfo(Dhizuku.getOwnerPackageName(), 0).uid
}.getOrNull()   // e.g. u0_aXXX; this is the uid of every Dhizuku newProcess child
```

Run a command (synchronous binder; do it off the main thread):

```kotlin
fun runDhizukuShell(vararg cmd: String): Pair<Int, String>? {
    val p = try {
        Dhizuku.newProcess(arrayOf(*cmd), null, null)   // (String[] cmd, String[] env, File dir)
    } catch (e: IllegalStateException) {                // "binder haven't been received"
        return null                                     // Dhizuku not initialized / not reachable
    } catch (e: SecurityException) {                    // "Permission Denial: remote_process ..."
        return null
    } catch (e: RuntimeException) {                     // wrapped RemoteException
        return null
    }
    return try {
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        val code = p.waitFor()
        code to out
    } finally {
        runCatching { p.destroy() }
    }
}
```

Error handling / binder death:

| Situation | Behaviour |
|---|---|
| `init()` false | Dhizuku not installed / not device owner / disabled in Dhizuku settings / provider call blocked (`queries` missing) |
| `requestPermission` before `init` | `IllegalStateException("Dhizuku.init(context) must be called first")` |
| No permission | `isPermissionGranted()==false`; privileged calls throw server-side `SecurityException` (`Permission Denial: remote_process is not allowed from pid=..., uid=...`) → surfaces as `SecurityException` (AIDL stub) |
| Binder death | internal `linkToDeath` clears `remote`; next call re-runs `init(context)` transparently; if it fails → `IllegalStateException("binder haven't been received")` |
| `DhizukuRemoteProcess` after server death | streams/`exitValue()` throw `IllegalStateException("remote process has died")`; `isAlive()==false`; `waitFor()` returns `0`; `destroy()` is a no-op — do not treat that `0` as success |
| Client process dies | Dhizuku server destroys all remote processes it handed to that client (`linkToDeath(this::destroyProcesses)`) — no orphan `tar` |
| Recommendation | surface a distinct state `DHIZUKU_NOT_ACTIVE` and never block on `requestPermission` from a service; the callback arrives on the main thread |

Dhizuku API surface reality check: it exposes **no package install/uninstall/list call**. Anything beyond
`newProcess`/`remoteProcess`, `binderWrapper`/`remoteTransact`, UserService, delegated scopes and lock-task mode
requires you to use hidden system-service Binder interfaces *through* Dhizuku's identity (see §5 install/uninstall row).

---

## 3. What can actually read `/data/data/<other app>`?

### 3.1 The Linux truth

* `/data/data` (a.k.a. `/data/user/0`) is `drwxrwx--x system system` — "other" has **execute only**, no read,
  so uid 2000 cannot even list it.
* Each app dir is `drwx------ u0_aXX u0_aXX`, SELinux label `app_data_file`, domain `untrusted_app*`.
* Shell (uid 2000) is in groups `shell,input,log,adb,sdcard_rw,sdcard_r,net_*…` — none of which grant app-data
  access, and no Android permission grants it either. Shizuku's own README states it explicitly:
  *"For example, Shell (ADB) cannot access other apps' data files `/data/user/0/<package>`"*
  (`.scratch/shizuku/README.md:133`).

Empirically confirmed on the attached Android 11 emulator (§8): uid 2000 gets `Permission denied` on `/data/data`
and on `/data/data/com.google.android.gms`, while uid 0 lists and `tar`s the same dir successfully.

### 3.2 Summary

| Backend | uid | `/data/data/<other>` | `/data/data/<debuggable>` | APK files | `/sdcard/Android/data`+`obb` |
|---|---|---|---|---|---|
| Root (libsu) | 0 | **YES** | YES | YES | YES |
| Shizuku ADB (start.sh) | 2000 | **NO** (hard boundary) | YES **via `run-as`** | YES (`pm path` + world-readable) | YES (shell uid) |
| Shizuku root / Sui | 0 | **YES** | YES | YES | YES |
| Dhizuku `newProcess` | Dhizuku app uid (`u0_aXXX`) | **NO** | NO | YES (world-readable APKs / own pm? see §4) | NO (other apps' dirs) |

### 3.3 `run-as` — the only ADB-mode loophole, and only for debuggable apps

AOSP `system/core/run-as/run-as.cpp` (main branch, `.scratch/run-as.cpp`):

* usage: `run-as <package-name> [--user <uid>] <command> [<args>]`
* caller uid must be `AID_SHELL (2000)` or `AID_ROOT` (line 169) → **Shizuku ADB (2000) qualifies**
* rejects non-debuggable packages: `error(1, 0, "package not debuggable: %s", pkgname)` (line 217)
* `execvp(argv[cmd_argv_offset], argv+cmd_argv_offset)` — arbitrary command with the app's uid and a
  `runas_app` SELinux transition.

Commands (all verified forms):

```bash
# probe
adb shell run-as com.example.debug id
# -> uid=10xxx(u0_axx) ... context=u:r:runas_app:s0...

# list
adb shell run-as com.example.debug ls -l /data/data/com.example.debug

# binary tar to host (exec-out keeps stdout binary-clean)
adb exec-out run-as com.example.debug tar -cf - . > debug-app.tar
adb exec-out run-as com.example.debug sh -c 'tar -cf - . | gzip -1' > debug-app.tgz

# from inside Shizuku's shell (uid 2000) — same commands, executed by a UserService:
#   svc.exec("run-as com.example.debug tar -cf - . | gzip -1")   (or execStream for raw bytes)
```

I verified the pipe on the attached device: `run-as de.dbauer.expensetracker tar -cf - .` emitted 2560 bytes
through a pipe both from root and from uid 2000, and `run-as` on a non-debuggable package failed with
`package not debuggable`. **Yes, Shizuku shell can pipe tar through `run-as`** — the target app just has to be
debuggable. For release builds of third-party apps this is useless.

N.B. `adb backup` (the old `bmgr`-based path) is not a substitute: it is deprecated/disabled for most apps on
Android 12+ and cannot be relied on for Android 13–16 backups. Root or `run-as` are the only real paths here.

---

## 4. Dhizuku `newProcess` — exact identity and blunt limits

Evidence chain (all verified in source/bytecode):

1. `Dhizuku.newProcess(cmd, env, dir)` → `requireServer().remoteProcess(...)`
   (`dhizuku-api/.../Dhizuku.java:317-323`).
2. Server side: `DhizukuService.remoteProcess` first calls `enforceCallingPermission("remote_process")`, then
   `DhizukuProcess.get().remoteProcess(Arrays.asList(cmd), environment, dir)`
   (`dhizuku-server_api/.../DhizukuService.java:131-148`). So the **Dhizuku permission is mandatory**.
3. `DhizukuProcess extends AppProcess.None` (`dhizuku-server_api/.../DhizukuProcess.java`). The `AppProcess`
   library also ships `AppProcess.Root` / `AppProcess.RootSystem` subclasses — **Dhizuku deliberately uses
   `None`**, i.e. no su, no `app_process` escalation for this path.
4. `AppProcess.remoteProcess` → `ProcessManager.remoteProcess` whose bytecode is literally
   `new ProcessBuilder().command(cmd).directory(dir).environment().putAll(env).start()` executed **inside the
   Dhizuku server process** (`.scratch/appprocess`, `ProcessManager.class`).
   (`NewProcess`/`app_process` machinery in that library exists only to host `UserService` Binder stubs, and its
   `createBinder` path also runs as the server uid.)
5. Dhizuku's own `UserService` runs code inside the Dhizuku server process — same uid, same SELinux domain.

Consequences (blunt):

* The child is a **fork/exec of a normal third-party app**: uid = `com.rosan.dhizuku`'s app uid (`u0_aXXX`),
  gid same, SELinux `untrusted_app` (or the Dhizuku app's domain), no Linux capabilities, no Android permissions
  attached to a plain `sh` child.
* **It can NOT read `/data/data/<other app>`** (0700 + SELinux), cannot read `/data/system`, `/data/misc`,
  keystore, `dumpsys` privileged data, or other apps' `/sdcard/Android/data`/`obb` (app-uid scoped-storage rules
  apply exactly as for DataArk itself).
* **It can NOT run `su`** unless the *Dhizuku app's uid* has been granted root by the su manager — that is not a
  Dhizuku feature, not the default, and would show a Magisk prompt for `com.rosan.dhizuku`.
* It can run `id`, `ls`, `cat` on whatever an ordinary app can read: its own files, `/sdcard` media it has
  permission for, and **other apps' APKs** under `/data/app/...` (verified mode `-rw-r--r-- system system`,
  i.e. world-readable).
* Treat `Dhizuku.newProcess` as `Runtime.exec` inside a normal app with **device-owner Binder powers**, not as a
  root shell. If DataArk's backup needs `/data/data`, Dhizuku cannot help, period.
* Useful device-owner capabilities are Binder-level, not filesystem-level: silent **install/uninstall** (see §5),
  `clearApplicationUserData`, `setPermissionGrantState`, delegated scopes, `setApplicationHidden`,
  `setPackagesSuspended`, network logging, lock-task mode.
* One more caveat: Dhizuku 2.7.0 blocks ownership-changing DPM transactions (`setDeviceOwner`,
  `clearDeviceOwner`, …) over `remoteTransact` unless the user enabled "allow ownership changes"
  (`.scratch/dhizuku` app `MyDhizukuService.onRemoteTransact`). Do not design around transferring device ownership.

---

## 5. Capability matrix for a backup app + UX gating

### 5.1 Matrix (Android 13–16, `targetSdk 36`)

Legend: ✅ native support · ⚠️ conditional/with caveats · ❌ no.

| Capability | ROOT (libsu) | SHIZUKU ADB (uid 2000) | SHIZUKU root (uid 0) | DHIZUKU (device owner) |
|---|---|---|---|---|
| Read `/data/data/<pkg>` + `/data/user_de/<pkg>` (non-debuggable) | ✅ | ❌ hard uid/SELinux limit | ✅ | ❌ |
| Read private data of **debuggable** apps | ✅ | ⚠️ `run-as <pkg>` only | ✅ | ❌ |
| Write/restore app private data | ✅ | ❌ (except `run-as` debuggable) | ✅ | ❌ |
| Backup APKs (base + splits) + get paths | ✅ | ✅ `pm path`, world-readable | ✅ | ⚠️ via `binderWrapper(IPackageManager)` as owner; APKs are world-readable anyway |
| Read other apps' `/sdcard/Android/data` + `/sdcard/Android/obb` | ✅ | ✅ shell uid has access | ✅ | ❌ (normal app uid; not covered by MANAGE_EXTERNAL_STORAGE) |
| Read ordinary `/sdcard` media/DCIM/Documents | ✅ | ✅ shell has `MANAGE_EXTERNAL_STORAGE` | ✅ | ⚠️ only what DataArk's own storage permissions/SAF allow |
| Silent `pm install` / `pm uninstall` | ✅ | ✅ shell holds `INSTALL_PACKAGES`,`DELETE_PACKAGES` | ✅ | ⚠️ not in API; possible *only* if the PackageInstaller session/uninstall call is executed with Dhizuku's uid (device owner waiver) — see 5.3 |
| `pm clear` (wipe app data) / `clearApplicationUserData` | ✅ | ✅ shell holds `CLEAR_APP_USER_DATA` | ✅ | ✅ `DevicePolicyManager.clearApplicationUserData` (destructive!) |
| Grant/revoke runtime permissions | ✅ | ✅ shell holds `GRANT_RUNTIME_PERMISSIONS`/`REVOKE_*` | ✅ | ✅ `setPermissionGrantState` |
| `cmd`/`dumpsys`/`pm list`/package metadata | ✅ | ✅ shell holds `DUMP`,`PACKAGE_USAGE_STATS`,`QUERY_ALL_PACKAGES` | ✅ | ⚠️ hidden-API Binder calls only |
| Lock task / kiosk, device restrictions | n/a | n/a | n/a | ✅ only Dhizuku |
| No user confirmation for install | ✅ | ✅ | ✅ | ✅ (device owner install session) |

Shell-permission evidence for the Shizuku ADB column: AOSP `packages/Shell/AndroidManifest.xml`
(`.scratch/aosp/ShellManifest.xml`) grants uid 2000 `INSTALL_PACKAGES`, `DELETE_PACKAGES`,
`CLEAR_APP_USER_DATA`, `GRANT_RUNTIME_PERMISSIONS`, `REVOKE_RUNTIME_PERMISSIONS`, `MANAGE_EXTERNAL_STORAGE`,
`DUMP`, `READ_LOGS`, `WRITE_SECURE_SETTINGS`, etc. It does **not** grant filesystem access to app data —
permissions cannot override `0700`/SELinux.

### 5.2 Why the matrix has these sharp edges

* **APKs are not privileged data.** `/data/app/**/base.apk` is `-rw-r--r-- system system` (verified). Any app
  that can get the path (e.g. via `QUERY_ALL_PACKAGES`/queries, or `pm path` as shell) can read it. So APK backup
  is feasible even with no privilege at all; the real gates are package visibility and split discovery.
* **`/sdcard/Android/data` is not normal app storage on Android 11+** — apps (even with all-files access) can't
  read other apps' entries there; shell uid 2000 can. That is why Shizuku ADB is genuinely useful even without
  root.
* **Device owner is a system-API identity, not a Linux identity.** It bypasses *system-service permission checks*
  (install/uninstall, permission state, app data wipe) but has zero effect on file ownership/SELinux.

### 5.3 Silent install/uninstall through Dhizuku (qualified)

AOSP `PackageInstallerSession.checkUserActionRequirement` (`.scratch/PackageInstallerSession.java:1130-1140`):

> *"Device owners and affiliated profile owners are allowed to silently install packages, so the permission
> check is waived if the installer is the device owner."*

and `PackageInstallerService.uninstall` (`.scratch/PackageInstallerService.java:1440-1460`):

> *"Allow the device owner and affiliated profile owner to silently delete packages"*

This applies to the **installer identity**, i.e. the uid that owns the PackageInstaller session. Dhizuku's API
does not wrap install/uninstall, but `Dhizuku.binderWrapper(IPackageInstaller.Stub.asInterface(...))` /
`Dhizuku.remoteTransact(...)` / a Dhizuku `UserService` executes the transaction **as `com.rosan.dhizuku`**, so:

* create a `PackageInstaller.Session` through the wrapper → installer = Dhizuku → `USER_ACTION_NOT_REQUIRED`
  commits silently; stream the APK bytes into `openWrite()` (fd is dup'd to your app, so no copy through the
  binder).
* uninstall: call `IPackageInstaller.uninstall(versionedPackage, callerPackageName, …)` with
  `callerPackageName = Dhizuku.getOwnerPackageName()`, because `PackageInstallerService` calls
  `mAppOps.checkPackage(callingUid, callerPackageName)` for non-root/shell callers.
* Requires hidden-API stubs (`IPackageInstaller`, `PackageInstaller.SessionParams` internals) and the Dhizuku
  permission. Treat as advanced/optional; verify on each Android version.

### 5.4 Recommended UX gating for DataArk

Model capabilities explicitly, never a single "privileged" boolean:

```kotlin
enum class Backend { ROOT, SHIZUKU_ROOT, SHIZUKU_ADB, DHIZUKU, NONE }

data class Capabilities(
    val backend: Backend,
    val internalData: Boolean,      // only ROOT / SHIZUKU_ROOT
    val runAsDebuggable: Boolean,   // SHIZUKU_ADB + ROOT
    val apk: Boolean,               // all (path discovery differs)
    val externalAppData: Boolean,   // ROOT / SHIZUKU_*
    val packageOps: Boolean,        // install/uninstall/clear/pm
    val deviceOwnerOps: Boolean,    // DHIZUKU only
)
```

* **Startup probe order** (each independent, never blocks UI):
  1. `Shell.isAppGrantedRoot() == true` → `ROOT` (full).
  2. Shizuku binder alive + permission + `Shizuku.getUid() == 0` → `SHIZUKU_ROOT` (full).
  3. Shizuku binder + permission + uid `2000` → `SHIZUKU_ADB` (no internal data; APK + external + pm + run-as
     for debuggable).
  4. `Dhizuku.init()` + `isPermissionGranted()` → `DHIZUKU` (device-owner features only).
  5. `NONE`.
* **Gating rules**
  * The "App data (private)" backup category is enabled **only** for ROOT/SHIZUKU_ROOT. For SHIZUKU_ADB show it
    greyed with "ADB mode runs as the `shell` user and cannot read app-private data — start Shizuku with root or
    grant root"; for DHIZUKU say "device owner does not grant file access".
  * APK + `/sdcard/Android/data` + `obb` categories are enabled for SHIZUKU_ADB — advertise this, it is the main
    value of ADB mode.
  * Package install/uninstall/clear/permission-grant actions: ROOT/SHIZUKU_*; DHIZUKU behind an "advanced,
    device owner" experiment flag with per-action confirmation.
  * Debuggable apps: offer a per-app "try `run-as`" action only when `runAsDebuggable` is known, and probe it
    (don't guess) by executing `run-as <pkg> id` in the backend shell.
* **Doctor/diagnostics screen**: show backend, uid (`Shizuku.getUid()`, `id` output for libsu, `dhizukuServerUid`),
  `Shizuku.getVersion()`, `Dhizuku.getVersionName()`, whether permission granted, and a one-tap "why can't I read
  this app" check listing the three possible fixes (root / root-mode Shizuku / debuggable).
* **Never silently request root** on first launch; show why it is needed. Re-probe every `onResume` and on
  `onBinderReceived/Dead` (Shizuku restarts after reboot; root can be revoked by Magisk).
* Keep backend state per backup item: a job started as ROOT must fail cleanly (not silently degrade) if root
  disappears mid-run; if a user switches backend, restart the job rather than mixing uid semantics.
* Multi-process: if the backup worker runs in `:backup`, call `ShizukuProvider.enableMultiProcessSupport(...)`
  with the correct provider-process flag in every process (plus `requestBinderForNonProviderProcess(this)` in
  non-provider processes), and `Dhizuku.init()` per process (its provider call works per process). libsu's main
  shell is per-process too — no sharing via memory.

---

## 6. ADB setup / test plan

### 6.1 Install and start Shizuku

```bash
# 1. Install (download from https://shizuku.rikka.app/download/ or GitHub releases)
adb install -r Shizuku.apk                  # any current Shizuku release supports API 13.1.5

# 2a. Start in ADB mode (non-rooted devices). Android 11+ can also do this in-app via Wireless debugging.
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh

# 2b. Start in ROOT mode: open the Shizuku app and tap "Start" (it execs its bundled
#     nativeLibraryDir/libshizuku.so through su). Automating that exact path from adb is
#     device/build-specific and not recommended — use the app UI, or install Sui (Magisk) for
#     automatic root startup.

# 3. Verify the server is running
adb shell ps -A | grep -i shizuku
# expected: a process under moe.shizuku.privileged.api, uid shell (ADB) or root (ROOT)

# 4. Confirm mode from the app: Shizuku.getUid() -> 2000 (ADB) / 0 (ROOT)
```

Notes: ADB-mode Shizuku must be restarted after every reboot (Shizuku's own home screen says so); root mode can
auto-start on boot. `start.sh` accepts no arguments; the app writes it into its external files dir.

### 6.2 Install and activate Dhizuku

```bash
adb install -r Dhizuku-2.7.0.apk

# Device owner can only be set when the device has NO accounts and NO secondary users.
# (Remove accounts/users first; on many devices a factory reset is the practical path.)
adb shell dpm set-device-owner com.rosan.dhizuku/.server.DhizukuDAReceiver

# Verify
adb shell dumpsys device_policy | grep -i -A5 'Device Owner'
adb shell dpm list-owners   # newer Android releases
```

If it fails with "there are already some accounts on the device", remove accounts/users and retry.
Dhizuku must stay installed and enabled; disabling/uninstalling it revokes everything.

### 6.3 Granting permissions (the important part)

```bash
# ROOT: Magisk/SuperSU prompt appears on the first su; approve once.
adb shell su -c id                     # uid=0 = root available (device-dependent su syntax)

# SHIZUKU: DO NOT use pm grant. The grant lives in Shizuku's ClientManager, not in PackageManager.
# Correct path: in-app Shizuku.requestPermission(code) -> Shizuku permission dialog.
#   Evidence: Service.checkSelfPermission() == clientManager.requireClient(uid,pid).allowed
#             enforceCallingPermission() throws "<func> ... is not an attached client" for unknown callers
#   (the manifest permission API_V23 is dangerous/self-implemented; pm grant does not create a client record).

# DHIZUKU: DO NOT use pm grant. The grant is a Room DB row keyed by uid + caller signing-certificate hash.
# Correct path: in-app Dhizuku.requestPermission(listener) -> Dhizuku's RequestPermissionActivity (15 s
# auto-deny). Evidence: MyDhizukuService.checkCallingPermission() checks appRepo.findByUID()/allowApi/signature;
# a re-signed debug build therefore needs a fresh approval.
```

Verification one-liners for each backend (run the same command in all three and compare `id`):

```bash
adb shell id                                            # on user builds: uid=2000(shell)
adb -s <SERIAL> shell 'su shell sh -c "id; ls /data/data 2>&1 | head -2"'   # uid 2000 cannot list
adb shell su -c 'id; ls /data/data | head -3'                              # uid 0 can
adb shell run-as com.example.debug tar -cf - . | wc -c                     # debuggable only
```

### 6.4 Test matrix to run on a real Android 13–16 device

| # | Action | ROOT | SHIZUKU ADB | SHIZUKU root | DHIZUKU |
|---|---|---|---|---|---|
| 1 | `id` | uid 0 | 2000 | 0 | Dhizuku app uid |
| 2 | `ls /data/data/com.android.chrome` | contents | Permission denied | contents | Permission denied |
| 3 | `tar -cf - /data/data/com.android.chrome` byte count | >0 | 0 + error | >0 | 0 + error |
| 4 | `pm path com.android.chrome` + read base.apk | ok | ok | ok | ok |
| 5 | `ls /sdcard/Android/data` | ok | ok | ok | denied |
| 6 | `pm install -r <apk>` on a scratch package | ok | ok | ok | only via binderWrapper/UserService |
| 7 | kill Restart backend mid-job (force-stop Shizuku/Dhizuku) | shell dies → NoShellException path | `onBinderDead` → IllegalStateException | same | init retry → IllegalStateException |

---

## 7. Critical integration warnings (read before coding)

1. **Do not write against `Shizuku.newProcess` on 13.1.5** — it is `private` since 13.1.1 (public only in
   13.0.0/13.1.0, verified by javap on the AARs). Use `Shizuku.bindUserService` + your own AIDL. UI/skeleton code
   that assumes a one-line "run shell command" API will not compile and the workaround is a new module, not a
   tweak.
2. **Shizuku ADB ≠ backup backend for private data.** uid 2000 cannot read `/data/data`; nothing you declare in
   the manifest changes that. Only root-mode Shizuku/Sui or real root can. Say this in the UI instead of failing
   mysteriously at backup time.
3. **Dhizuku `newProcess` is an ordinary app process** (uid = Dhizuku app, `untrusted_app`); it cannot read
   `/data/data` or `su`. Dhizuku = device-owner system APIs (install/uninstall/clear/permission state), not file
   privilege.
4. **No `pm grant` for either manager.** Shizuku = ClientManager record; Dhizuku = DB row + signature. Grant via
   the managers' dialogs; expect re-approval after re-signing or reinstall.
5. **Shizuku binder arrives asynchronously and can die.** Use `addBinderReceivedListenerSticky` +
   `addBinderDeadListener`; never call `getUid()`/`requestPermission()` before the binder or you get
   `IllegalStateException("binder haven't been received")`. `getUid()` returns `-1` (not an exception) when the
   permission is missing — do not treat `-1` as "unknown uid".
6. **Multi-process DataArk:** `ShizukuProvider.enableMultiProcessSupport()` in each process using Shizuku; Dhizuku
   `init()` per process; libsu main shell per process. Otherwise the worker process silently never gets a binder.
7. **Provider correctness is fatal-but-silent:** authority `${applicationId}.shizuku`, exported, multiprocess
   false, `INTERACT_ACROSS_USERS_FULL`. Missing provider = no binder ever (no exception at startup).
8. **minSdk:** Shizuku `api` AAR requires minSdk ≥ 24 at manifest merge (DataArk is 26 — fine, but don't add a
   lower-minSdk library that overrides).
9. **Never do Binder/root work on the main thread** (`bindUserService`, `Dhizuku.newProcess`, `Shell.getShell()`,
   `unbindUserService`, `tar`) — and do not rely on async `Shell.getShell(cb)` firing when there is no shell
   (it doesn't).
10. **UserService lifecycle:** Shizuku does not kill the process on unbind; implement `destroy()` (AIDL transaction
    16777114 declared / 16777115 actual) and `System.exit(0)`, or DataArk will leak a root/shell process forever.
    `daemon(true)` only for long-lived workers.
11. **Binary data must travel over fds, not Binder Strings** (~1 MB transaction limit, UTF-8 text semantics).
12. **Dhizuku setup fragility:** needs no accounts/users on the device, must remain installed and enabled; the
    device-owner identity is what your permission and silent installs rely on. Also Dhizuku 2.7.0 deliberately
    blocks device-owner ownership changes via `remoteTransact` unless the user allows it.
13. **Security:** a `UserService` or root shell runs commands you build with root/shell uid. DataArk must never
    interpolate app names/paths/user input into shell strings without strict validation/quoting; prefer argv
    arrays (`ProcessBuilder(listOf("tar","-cf",...))`) and fixed paths under `/data/data/<validated-package>`.
    Restrict the exported AIDL service from other apps (Shizuku UserService is reachable through Shizuku only,
    but validate the caller where possible).
14. **R8/ProGuard:** keep the AIDL Stub, `ShellUserService`, both constructors (`@Keep`), and the Shizuku
    `ShizukuProvider`; otherwise `bindUserService` fails with `ClassNotFoundException` at runtime only on release
    builds.

---

## 8. Evidence appendix (what was inspected, what was executed)

Artifacts (all under the shared workspace `.scratch/`):

* `.scratch/shizuku/x_api-13.1.5/classes.jar` — javap: `Shizuku.newProcess` is `private`, methods/listeners as
  listed in §2.2; `.scratch/shizuku/v13.0.0`, `v13.1.0` — same method is `public static` there; `v13.1.1` — private.
* `.scratch/shizuku/x_provider-13.1.5/AndroidManifest.xml` + `ShizukuProvider.class` — permission/meta-data,
  `attachInfo` throws on `multiprocess`/`exported`, authority checked against `getPackageName() + ".shizuku"`.
* `.scratch/shizuku/README.md` — exact provider block (lines 59-71), permission flow (77-123), ADB-vs-root uid
  (125-133: *"Shell (ADB) cannot access other apps' data files"*), UserService/destroy transaction, changelog
  13.1.1 (*"Prepare to remove Shizuku#newProcess"*).
* `.scratch/shizuku/Shizuku.java`, `ShizukuProvider.java`, `Service.java` — `requireService()` →
  `IllegalStateException("binder haven't been received")`; `getUid()` → `-1` on `SecurityException`;
  `checkSelfPermission()` → `clientManager.requireClient(uid,pid).allowed`; `enforceCallingPermission()` →
  "is not an attached client".
* `.scratch/dhizuku/out/AndroidManifest.xml` + `out/classes.jar` — merged permission/queries, API surface.
* `.scratch/dhizuku-src/Dhizuku-API-main` — `Dhizuku.java` (init/permission/newProcess/request timeout 15 s),
  `DhizukuService.java` (`enforceCallingPermission("remote_process")`, ProcessBuilder path),
  `DhizukuProcess.java` (`extends AppProcess.None`), `RemoteProcess.java`, `DhizukuRemoteProcess.java`
  (death → `IllegalStateException("remote process has died")`, `waitFor()==0`).
* `.scratch/appprocess/aap.aar` — `ProcessManager.remoteProcess` bytecode = `ProcessBuilder(...).start()`.
* `.scratch/aosp/ShellManifest.xml` — uid-2000 permission set.
* `.scratch/PackageInstallerSession.java` line ~1130: device owner silently installs; `.scratch/PackageInstallerService.java`
  lines ~1440-1460: device owner silently deletes.
* `.scratch/run-as.cpp` — uid `AID_SHELL`/`AID_ROOT` only, rejects non-debuggable, `execvp` argv.
* `.scratch/libsu/jx/classes.jar` + libsu `Shell.java` — signatures, su→sh fallback, `NoShellException`, defaults.
* `.scratch/dhizuku/app-manifest.xml` — Dhizuku permission `protectionLevel="dangerous"`;
  `.scratch/shizuku-manager-manifest.xml` — `API_V23` protectionLevel `dangerous`;
  Dhizuku `MyDhizukuService.kt` — DB+signature permission check; `RequestPermissionActivity.kt` — 15 s auto-deny;
  Shizuku `RequestPermissionActivity.kt` — dialog-based client record.

Live device checks (Android 11 / SDK 30 emulator, `sdk_gslim_x86_64`, userdebug; ADB shell itself was uid 0, so
uid 2000 was simulated with the built-in toybox `su shell`):

```
uid=2000(shell) ... ; ls -ld /data/data      -> drwxrwx--x system system
                     ls /data/data           -> Permission denied
                     ls /data/data/com.google.android.gms -> Permission denied
uid=0 ...            ls /data/data/com.google.android.gms -> app_APP app_chimera app_dg_cache ...
                     tar -cf /dev/null -C /data/data/com.google.android.gms . -> exit 0
uid=2000 ...         ls /sdcard/Android/data -> com.google.android.gms (allowed)
                     run-as com.google.android.gms id -> "package not debuggable"
uid=2000 ...         run-as de.dbauer.expensetracker tar -cf - . | wc -c -> 2560 bytes
uid=0 ...            same -> 2560 bytes
root                 /data/data/de.dbauer.expensetracker -> drwx------ u0_a74 u0_a74
                     /data/app/.../base.apk -> -rw-r--r-- system system (world-readable)
```

Caveat: this device is Android 11, not 13–16, and its adbd runs as root (userdebug). The Android 13–16 statements
above are grounded in AOSP `main` sources, the libraries' bytecode and upstream docs, not in device tests; re-run
§6.4 on a retail Android 13–16 device before shipping.
---

## 9. Audit of DataArk's current privilege layer (read-only, snapshot 2026-11-15 ~09:34)

The app source was authored by other agents while this research ran. I did not modify it; the following is a
read-only audit against the findings above, at the timestamp above (re-check before merging).

**Matches the research (no action):**
* `app/src/main/AndroidManifest.xml` has the exact Shizuku provider block (`${applicationId}.shizuku`,
  exported, `multiprocess=false`, `INTERACT_ACROSS_USERS_FULL`) and both AARs are dependencies
  (`gradle/libs.versions.toml`: shizuku 13.1.5, dhizuku 2.7.0, libsu 6.0.0).
* `PrivilegeManager.probe*` maps capabilities correctly: Root and `Shizuku.getUid()==0` → `DataAccess.FULL`;
  Shizuku uid 2000 → `DEBUGGABLE_ONLY`; Dhizuku non-zero uid → `APK_ONLY`; `NONE` otherwise.
* `BackupEngine` redirects tar output to `File(backupRoot, ...)`; with `backupRoot` on
  `getExternalFilesDir` / `/storage/emulated/0`, both shell (uid 2000) and root can write there, so the
  `> DATA_OUT` / `cat DATA_ARCHIVE |` handoffs are sound.
* `run-as` paths are only used for the `DEBUGGABLE_ONLY` flow, and restore uses
  `cat archive | run-as pkg sh -c "cd /data/data/pkg && tar -xpf -"` — exactly the pattern in §3.3.

**Risks / findings worth acting on:**
1. `ShizukuShellRunner.kt:19-27` bypasses the (removed) public `Shizuku.newProcess` by calling the hidden AIDL:
   `IShizukuService.Stub.asInterface(Shizuku.getBinder()).newProcess(...)` + raw `moe.shizuku.server.IRemoteProcess`.
   This **works on Shizuku 13.1.5** (server `Service.java:307` still implements `newProcess` and enforces
   `enforceCallingPermission("newProcess")`), and `dev.rikka.shizuku:aidl` is on the compile classpath via the
   `api` POM. But it is precisely the deprecated transaction upstream plans to remove: keep it behind the
   existing `try/catch` and plan a `bindUserService` fallback; do not assume it survives future Shizuku releases.
   Also note it depends on `IShizukuService`/`IRemoteProcess` hidden packages — pin the AAR version.
2. `DhizukuShellRunner.kt:15` calls `Dhizuku.isPermissionGranted()` unguarded. `Dhizuku.isPermissionGranted()`
   → `requireServer()` throws `IllegalStateException("binder haven't been received")` if `init()` was never
   called (e.g. backup resumed in a fresh process before `probeDhizuku`). Wrap in `runCatching` or call
   `Dhizuku.init(context)` first.
3. `PrivilegeManager.kt:286` (`probeDhizuku` detail) says Dhizuku mode can back up "APK / 外部数据". Dhizuku
   `newProcess` is the Dhizuku app uid, so it **cannot read other apps' `/sdcard/Android/data` or `obb`** either.
   Change to "APK / 应用列表 / Device Owner 操作".
4. `PrivilegeManager.kt:226-238`: `Shizuku.getUid()` can return `-1` (permission lost on the server between
   `checkSelfPermission()` and `getUid()`); the current code labels it "ADB 模式 (UID -1)" with
   `DEBUGGABLE_ONLY`. Treat `uid <= 0` explicitly (`-1` → not granted; refresh).
5. `RootShellRunner.kt:14/18`: `Shell.getShell()` can throw `NoShellException` and `Shell.cmd(...)` uses the
   cached main shell; the exception is not caught (`ShellCommandException` is expected by callers). Wrap both.
   The `shell.isRoot` guard is correct — keep it (the `sh` fallback is a non-root, app-uid shell).
6. Product gap: Dhizuku is treated as an APK-only fallback, but its unique value (silent install/uninstall,
   `clearApplicationUserData`, permission grant state via `binderWrapper`/`remoteTransact` — §5.1) is not wired
   at all. Either implement those as explicit "Device Owner tools" or drop the Dhizuku backend from the UI to
   avoid a backend that adds no capability over plain app code.
