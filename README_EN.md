# stateskeletonlayout

**English** | [中文](README.md) | [Changelog (Chinese)](CHANGELOG.md)

A 2-in-1 Android View widget combining page state management (Content / Loading / Empty / Error) and skeleton loading (Solid / Shimmer / Pulse). Pages only submit target states declaratively without directly coordinating state view visibility, skeleton animations, minimum display durations, or animation lifecycles.

- Library module: `:stateskeletonlayout` (namespace `com.sfyc.ssl`)
- Sample application: `:sample` (source package `com.sfyc.demo`, namespace / applicationId `com.sfyc.demo.ssl`, 20 demo screens)
- UI: Android View System + XML + ViewBinding; Jetpack Compose is not introduced
- Environment: Kotlin 2.2.10, AGP 8.13.2, Gradle 8.14, `compileSdk` / `targetSdk` 36, `minSdk` 26, JVM 17
- Library explicit dependencies: AndroidX Annotation only (no Core, Lottie, SVGA, Material, Navigation, or Lifecycle); Kotlin standard library is added automatically by the Kotlin plugin

Module and package name migrations have been completed.
The sample source package differs from its namespace; R / ViewBinding use `com.sfyc.demo.ssl`. The new sample is installed independently under the new applicationId.
Callers of the original module and legacy package need to update dependencies, imports, and XML tags accordingly (historical migration notes are kept in internal docs and not published in the public repository).

## Installation

### JitPack (0.0.3)

This project publishes the library module's Release AAR, source jar, and API documentation jar via JitPack. The sample app is not included in the published artifacts.
Add the repository in the host project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io") {
            content {
                includeGroup("com.github.sfyc23")
            }
        }
    }
}
```

Then add the dependency in the app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.sfyc23:StateSkeletonLayout:0.0.3")
}
```

See the [JitPack Project Page](https://jitpack.io/#sfyc23/StateSkeletonLayout/0.0.3) for build status and logs.
JitPack builds artifacts on the first request for a version; dependencies can only be resolved after the build succeeds.

When upgrading from `0.0.1`, recompile the app and any libraries that use `SslGlobalDefaults` or `StateLayoutDiagnostics`.
New fields in these public data classes change their JVM constructor and `copy` signatures; precompiled AARs may require updated builds. See the [changelog](CHANGELOG.md).

### Local Module

If you need to modify the library source code directly, you can also depend on it as a local module in your host project:

```kotlin
dependencies {
    implementation(project(":stateskeletonlayout"))
}
```

## Quick Start

### 1. Minimal XML

```xml
<com.sfyc.ssl.StateSkeletonLayout
    android:id="@+id/state_layout"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:ssl_emptyLayout="@layout/state_empty"
    app:ssl_errorLayout="@layout/state_error"
    app:ssl_errorRetryViewId="@id/retry_button"
    app:ssl_useSkeleton="true"
    app:ssl_skeletonSource="content"
    app:ssl_skeletonEffect="shimmer">

    <!-- Sole business content child View (exactly one; fewer or more will throw an exception during inflation) -->
    <include layout="@layout/content_profile" />

</com.sfyc.ssl.StateSkeletonLayout>
```

### 2. Switching State in Fragment / ViewBinding

```kotlin
class ProfileFragment : Fragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val stateLayout = binding.stateLayout
        // Error retry: one-way event; the widget does not switch state or send network requests automatically.
        stateLayout.setOnRetryClickListener {
            viewModel.onRetryClicked()
        }
        viewModel.uiState.observe(viewLifecycleOwner) { ui ->
            stateLayout.render(ui.toLayoutState())
        }
    }
}
```

### 3. Mapping ViewModel StateFlow to States

```kotlin
// The ViewModel only dictates the "target state"; display rules (delay, minimum duration, latest-wins) are handled by the widget.
val uiState: StateFlow<ProfileUiState> = repository.profile
    .map { result ->
        when {
            result.isLoading -> ProfileUiState.Loading
            result.error != null -> ProfileUiState.Error(result.error.message)
            result.data == null -> ProfileUiState.Empty
            else -> ProfileUiState.Content(result.data)
        }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProfileUiState.Loading)

// In Fragment:
viewLifecycleOwner.lifecycleScope.launch {
    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { binding.stateLayout.render(it.toLayoutState()) }
    }
}
```

### 4. Programmatic Creation (Without XML)

```kotlin
val layout = StateSkeletonLayout(context)
layout.setContentView(contentView) // Can only be called once
layout.setStateViewLayout(StateLayoutState.EMPTY, R.layout.state_empty)
layout.setStateViewLayout(StateLayoutState.ERROR, R.layout.state_error)
```

Programmatically created instances can use the same retry mechanism as in XML, with the default throttle interval remaining at 500 ms:

```kotlin
layout.errorRetryViewId = R.id.retry_button
layout.retryClickThrottleMillis = 500L
layout.setOnRetryClickListener { viewModel.onRetryClicked() }
// Explicitly controlled by business logic during requests; the widget does not infer network status or modify button enabled state.
layout.retryClicksEnabled = !uiState.isRetrying
```

Programmatic instances apply the initial state upon first attachment, allowing layout resources to be configured beforehand; an explicit `render` prior to attachment takes precedence over the initial state.

### 5. Works Without Configuring State Pages (Built-in Fallback Layouts)

`EMPTY` / `ERROR` layouts **no longer throw when left unconfigured**; they fall back to the library's
built-in pages: a single centered line of text, plus a retry button on the error page. A minimal
integration only needs the business content itself:

```xml
<com.sfyc.ssl.StateSkeletonLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:ssl_useSkeleton="true">

    <include layout="@layout/content_profile" />

</com.sfyc.ssl.StateSkeletonLayout>
```

The built-in error page ships with a retry button. When `ssl_errorRetryViewId` is not set explicitly,
the widget adopts that button as the unified retry entry point — no extra configuration needed. The
built-in layout's IDs are public, so you can replace the text:

```kotlin
// IDs of the library's built-in layout (declared in the library's ids.xml, referenceable directly).
com.sfyc.ssl.R.id.ssl_builtin_state_root     // Root LinearLayout
com.sfyc.ssl.R.id.ssl_builtin_state_message  // Message TextView
com.sfyc.ssl.R.id.ssl_builtin_state_retry    // Retry button (error page only)

layout.setStateDescription(StateLayoutState.ERROR, "Network unavailable")
// Or change the text directly: obtain the root node, then look up the inner widget
layout.getStateView(StateLayoutState.ERROR)
    ?.findViewById<TextView>(com.sfyc.ssl.R.id.ssl_builtin_state_message)
    ?.text = "Network unavailable, please check your connection"

// Projects that want missing configuration to fail fast can disable the fallback:
StateSkeletonLayoutDefaults.install(SslGlobalDefaults(useBuiltInStateLayouts = false))
```

The built-in strings are `ssl_builtin_empty_message` / `ssl_builtin_error_message` / `ssl_builtin_retry`
(one set per locale); hosts can override them with resources of the same name. The built-in layouts use
only `android:*` attributes and **introduce no Material / AppCompat dependency**.

### 6. Using It with Jetpack Compose

The library itself **pulls in no Compose dependency** and ships no Compose artifact; wrap it with
`AndroidView` inside a Compose screen:

```kotlin
@Composable
fun ProfileScreen(state: StateLayoutState, content: @Composable () -> Unit) {
    val holder = remember { mutableStateOf<StateSkeletonLayout?>(null) }
    AndroidView(
        factory = { context ->
            StateSkeletonLayout(context).apply {
                addView(ComposeView(context).apply { /* or an XML layout */ })
                holder.value = this
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    LaunchedEffect(state) { holder.value?.render(state) }
}
```

Two things to watch: `StateSkeletonLayout` requires exactly one business content child View; when that
child is a `ComposeView`, content-based shape extraction can only see the `ComposeView` layer itself,
so **use template extraction if you want a skeleton** (`ssl_skeletonSource="template"`).

## Global Design Defaults

Install once, and all new instances inherit automatically; XML attributes and runtime setters can override individual properties.
Ideal for unifying brand visuals (mask colors, corner radii, effects, minimum loading duration, etc.).

### Installation (Application.onCreate)

```kotlin
StateSkeletonLayoutDefaults.install(
    SslGlobalDefaults(
        useSkeleton = true,
        skeletonSource = SkeletonSource.CONTENT,
        effect = SkeletonEffect.PULSE,
        maskColor = getColor(R.color.brand_skeleton),
        minimumLoadingDurationMillis = 500L,
    )
)
```

- **Partial Override**: Fields set to `null` in `SslGlobalDefaults` do not participate in overriding and retain the next-layer default value.
- **Monolithic Replacement by install**: Repeated calls take the latest invocation; no field-level merging is performed.
- **Effective Timing**: Merged only once during widget construction; already created instances are unaffected by subsequent `install` calls.
- **Layout Resource Properties Are Not Overridden** (`emptyLayout` / `errorLayout` / `loadingLayout` / `skeletonTemplate` / `errorRetryViewId`): These properties vary per screen and remain individually configured via XML or at runtime.

### Optional: Theme Attribute Helper Layer

```xml
<style name="AppTheme" parent="...">
    <item name="ssl_stateSkeletonLayoutStyle">@style/AppSslDefaults</item>
</style>

<style name="AppSslDefaults" parent="ssl_StateSkeletonLayout">
    <item name="ssl_maskColor">@color/brand_skeleton</item>
    <item name="ssl_skeletonEffect">pulse</item>
</style>
```

### Priority Chain (Low → High)

```
Built-in Resources / Constants < Theme Defaults < Global Config < XML Explicit Style < XML Direct Attributes < Runtime Setters
```

When the same property is set in both Theme and Global Config, **Global Config takes precedence**. The parent style of an explicit style also belongs to the page layer; `?attr` references within it are still resolved against the active Theme.

### Test Isolation

Call `StateSkeletonLayoutDefaults.reset()` between unit test cases to prevent cross-test pollution.

## Skeleton Modes

### Content-based vs. Template-based Shape Extraction

| | Content-based (`content`) | Template-based (`template`) |
| --- | --- | --- |
| Shape Source | Traverses visible leaf Views in business content | Standalone XML template |
| Applicability | Content has finished layout and has valid dimensions when entering Loading | Content has zero dimensions, or list first-screen placeholder |
| Fallback Behavior | Gracefully degrades to a solid static mask if no valid rectangles exist (Debug logs warning) | Fails fast on entry if template is invalid; temporarily empty geometry after verified template shrinks degrades to static mask |
| Declaration | `ssl_skeletonSource="content"` (Default) | `ssl_skeletonSource="template"` + `ssl_skeletonTemplate="@layout/…"` |

Templates only define shapes and do not carry real text or business data:

```xml
<com.sfyc.ssl.StateSkeletonLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:ssl_useSkeleton="true"
    app:ssl_skeletonSource="template"
    app:ssl_skeletonTemplate="@layout/skeleton_profile_template">
    <include layout="@layout/content_profile" />
</com.sfyc.ssl.StateSkeletonLayout>
```

### Three Mutually Exclusive Effects

- `solid`: Static solid color, no animator.
- `shimmer` (Default): Shimmer sweeps across, respecting RTL direction (`start_to_end` sweeps right-to-left under RTL).
- `pulse`: Overall opacity cosine breathes within `[pulseMinAlpha, pulseMaxAlpha]`.

When system animations are disabled, it automatically degrades to static rendering without leaving blank areas.

### RecyclerView: Template Only, No Adapter Replacement

The initial release does not support list skeletons that replace Adapters (which would break scroll positions, state restoration, and business Adapter lifecycles).
Recommended approach: Write a dedicated template for the list with enough static repeated rows to cover the first viewport; design Grid layouts according to the target span count:

```xml
app:ssl_skeletonSource="template"
app:ssl_skeletonTemplate="@layout/skeleton_list_template"
```

**For list skeletons, prefer one instance covering the whole screen rather than one
`StateSkeletonLayout` per item.**

Two reasons, both of which still hold after the recent rework:

- **Mask memory**: each instance holds one `ALPHA_8` mask Bitmap as large as itself for its whole
  lifetime. Twenty item-level instances means twenty masks resident at once.
- **Animation cost**: the library now drives skeleton animation from a **process-level shared clock**
  (N instances drive one `ValueAnimator`, one main-thread callback per frame), so this cost is no
  longer multiplied by N — but the mask is still one per instance.

A full-screen template is therefore currently the only approach that preserves both memory and frame
rate: place around eight static placeholder rows in the template. It does not continue as you scroll
(that is the "infinite list skeleton" territory, not supported yet).

If you genuinely need item-level skeletons, measure the real footprint first via `diagnostics()`'
`maskBytes` / `maskBuildCount`, then decide on a scale limit.

When switching span counts, update the template and refresh synchronously:

```kotlin
stateLayout.skeletonConfig = stateLayout.skeletonConfig.copy(
    templateLayoutResId = R.layout.skeleton_grid_template,
)
stateLayout.invalidateSkeleton()
```

### Configuration Changes (Dark Mode / Font Scale / Locale / RTL)

The widget overrides `onConfigurationChanged` and handles the following automatically:

- **Skeleton colors and corner radius**: only when they **still equal the values last resolved from
  resources** (that is, the host never overrode them explicitly) are they re-resolved from
  `ssl_mask_default` / `ssl_highlight_default` / `ssl_corner_radius_default` under the new
  Configuration. If the host set any of them via XML / Global / setter, they are left untouched —
  brand colors won't be displaced by resource defaults.
- **State page cache**: all three states' caches are cleared, and the currently rendered state page is
  rebuilt immediately under the new Configuration (state pages may carry fixed dp dimensions and static
  text). Layout resource declarations are preserved.
- **Skeleton template**: the renderer decides for itself whether to re-inflate, based on
  `configuration.hashCode()`.
- **RTL shimmer**: `SkeletonOverlayView.onRtlPropertiesChanged` rebuilds the shader and invalidates the
  mask.

If your Activity declares `android:configChanges` and thus does not recreate Views, the logic above
applies; if it does not, the View is recreated and the Configuration is naturally read by a new
instance. Neither case requires extra work from the business code.

### Updating Configuration at Runtime

Configurations are immutable snapshots and replaced as a whole (individual mutations to mutable fields are rejected by unified validation):

```kotlin
// Switching effects: Mask is not rebuilt; animator switches immediately.
stateLayout.skeletonConfig = stateLayout.skeletonConfig.copy(
    effect = SkeletonEffect.PULSE,
)
// Manually refresh when content changes cause shape updates.
stateLayout.invalidateSkeleton()
```

### Node Rules and Resource Defaults

```kotlin
stateLayout.setSkeletonNodeConfig(R.id.avatar, SkeletonNodeConfig(shape = SkeletonShape.CIRCLE))
stateLayout.setSkeletonNodeConfig(R.id.divider, SkeletonNodeConfig(excluded = true))
stateLayout.setSkeletonNodeConfig(R.id.title, SkeletonNodeConfig(cornerRadius = 4f)) // px
stateLayout.setSkeletonNodeConfig(R.id.title, null) // Restore global corner radius
// Create config consistent with current resources; retain page parameters like template source when copying.
val defaults = SkeletonConfig.from(context)
stateLayout.skeletonConfig = stateLayout.skeletonConfig.copy(
    maskColor = defaults.maskColor,
    highlightColor = defaults.highlightColor,
    maximumMaskPixels = 2_000_000L,
)
```

Rules are applied by ID to both content and templates; multiple nodes sharing the same ID share the rule, and missing IDs result in a no-op. Excluding a container excludes its entire subtree. If final content geometry is entirely empty, a solid static fallback is performed.
Static feedback is maintained in layout editors (`isInEditMode` does not run infinite skeleton animators); preview is no substitute for on-device verification.

## Custom Loading Layout and Lottie

The library does not depend on Lottie. Creation callbacks handle binding, visibility callbacks handle start/stop, and release callbacks handle cleanup:

```xml
<com.sfyc.ssl.StateSkeletonLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:ssl_useSkeleton="false"
    app:ssl_loadingLayout="@layout/state_loading_lottie">
    <include layout="@layout/content_profile" />
</com.sfyc.ssl.StateSkeletonLayout>
```

```kotlin
var lottieView: LottieAnimationView? = null

stateLayout.setOnStateViewCreatedListener { _, created, createdView ->
    // Called once when state layout is first inflated, ideal for finding Lottie and holding a reference.
    if (created == StateLayoutState.LOADING) {
        lottieView = createdView.findViewById(R.id.lottie_view)
    }
}
stateLayout.setOnLoadingVisibilityChangedListener { _, visible, loadingView ->
    // Also notified for layout replacement within LOADING, aggregate hidden states, and detach / attach.
    if (visible) {
        lottieView = loadingView?.findViewById(R.id.lottie_view)
        lottieView?.playAnimation()
    } else lottieView?.pauseAnimation()
}
stateLayout.setOnStateViewReleasedListener { _, state, releasedView ->
    if (state == StateLayoutState.LOADING) {
        releasedView.findViewById<LottieAnimationView>(R.id.lottie_view)?.cancelAnimation()
    }
}
```

Fragments should also pause in `onStop`, resume in `onStart` based on actual visibility, and cancel animations / detach callbacks in `onDestroyView`. See the Lottie sample for full implementation.

Creation, dynamic binding, and release callbacks must only operate on the passed View and must not re-enter `render` or modify widget configuration. Queries and public callbacks return original layout root nodes; internal wrapper containers are isolated by the library. Render callbacks indicate state commitment rather than fade-in completion, and do not imply the window remains visible.

## SVGA Loading Sample

The "SVGA Loading" item on the sample homepage demonstrates using a local animation as a custom Loading page. Entering the page simulates a 7-second loading process automatically;
the "Loading" button keeps loading continuously, the Content / Empty / Error buttons cancel simulated tasks and switch states, and error page retry re-executes the simulated load.
Lottie and other samples remain intact; SVGA dependencies are confined strictly to `:sample`.

The pinned version is `com.github.yyued:SVGAPlayer-Android:2.6.1`. When integrating into an app, JitPack needs to be configured in `settings.gradle.kts` under `dependencyResolutionManagement.repositories` with content filtering including `com.github.yyued`, before adding the dependency. This project already includes the Parcelize runtime; the legacy SVGA extension runtime introduces duplicate classes, which should be excluded as follows:

```kotlin
implementation("com.github.yyued:SVGAPlayer-Android:2.6.1") {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions-runtime")
}
```

`tools:replace="android:allowBackup,android:label"` in the `sample` Manifest preserves existing app backup settings and label, resolving merge conflicts caused by SVGA AAR's built-in `<application>` attributes. Release builds apply official Wire / SVGA Proto keep rules; see `sample/proguard-rules.pro`.

Assets are packaged in `sample/src/main/assets/loading.svga`, sourced from
[SVGAPlayer-Web-Lite loading.svga](https://github.com/svga/SVGAPlayer-Web-Lite/blob/master/__test__/svga/loading.svga).
No animation download or internet permission is needed at runtime. The asset is SVGA 2.0 data, canvas size `375 × 667`, 90 frames, 15 FPS, nominally ~6s per loop; the sample uses a `144dp × 256dp` `fitCenter` View to fully display the canvas.

Integration is demonstrated in `sample/src/main/java/com/sfyc/demo/svga/SvgaLoadingFragment.kt` and two XML files:

- `fragment_svga.xml`: Configures `ssl_useSkeleton="false"` and `ssl_loadingLayout="@layout/state_loading_svga"`.
- `state_loading_svga.xml`: `SVGAImageView` disables auto-play and leaves `source` unset; on parse failure, it preserves progress indicators and loading text.
- The Fragment asynchronously parses via `SVGAParser(applicationContext).decodeFromAssets`, discarding stale results using weak references, parse generation tokens, and target View identity.
- Loading plays when window is visible, View lifecycle reaches STARTED, resources are ready, and system animations are enabled; it stops upon hide or backgrounding, and replays from frame 0 when resumed.
- Normal hiding preserves parsed entities; layout release or `onDestroyView` stops and calls `clear()`, with late unbound entities cleaned up immediately.
- When system animations are turned off, only static loading text is retained. Simulated requests, animation parsing, and single-loop completion events remain independent.

Repeated loading on the same page reuses the parsed entity; after parse failure, each explicit retry triggers at most one parse attempt.
Configuration changes rebind the page and restart the 7-second simulation without saving animation frame positions or remaining request time.

The added `SvgaLoadingFragmentTest` covers 7-second timing, task cancellation, parse failure, invalid playback parameters, late callbacks, background recovery, and disabled system animations, verifying pixel changes across frames using real assets and Native Canvas. `SvgaLoadingSmokeTest` tests entry, playback, and lifecycle smoke once connected to a device.
JVM Native Canvas draw checks do not substitute for on-device, emulator, or R8 Release runtime verification.

SVGA integration verification record (2026-10-11; see the [changelog](CHANGELOG.md) for verification of this commit): 54 sample tests and 127 library JVM / Robolectric tests (total 181) passed with 0 failures, errors, or skips; 18 new SVGA test cases executed on API 26 / 34, including real asset parsing and Native Canvas frame pixel verification.
Debug, R8 Release, and AndroidTest APKs all assembled successfully; Debug / Release Lint reports 0 Errors / 58 Warnings, where this screen adds 1 Overdraw and 4 ButtonStyle warnings, following existing sample backgrounds and button styles without globally suppressing warnings.
7 new dependency verification attachments were checked against JitPack / Maven Central files; the library runtime dependency graph contains neither SVGA nor Wire.
A debug-keystore-signed `build/svga-implementation/sample-release-validation.apk` passed v2 / v3 signature verification for local verification only. Note: that APK is a **manually signed, out-of-repo artifact**; this repository contains no `signingConfig` or keystore. A standard Release APK built with the commands in the Versioning section is always **unsigned** — do not conflate the two.
Device tasks were attempted but skipped due to `No connected devices!`; device smoke tests, R8 Release runtime checks, TalkBack, and memory scenarios still require manual follow-up.

The [official SVGA Android repository](https://github.com/svga/SVGAPlayer-Android) has been archived and is no longer maintained. This sample pins version 2.6.1; new project integrations still need to verify actual dependency resolution and Release runtime performance.

## State Timing

### requestedState vs renderedState

- `requestedState`: Most recently requested target (caller's intent).
- `renderedState`: State already presented to the user (visual reality).

A successful request does not mean the visual screen has switched immediately: `render(LOADING)` might not yet be presented due to display delay, or may be skipped entirely if the request finishes too quickly. Please use `renderedState` (or render callbacks) for assertions and telemetry.
Transactional commits are used internally: `renderedState` only advances once the target View is genuinely visible. If synchronous configuration or View inflation fails, the old screen and old state are preserved, allowing the same target to be re-submitted once corrected.

### Loading Show Delay (`ssl_loadingShowDelay`, Default 0ms)

Delays actual presentation for a specified period after requesting Loading. If Content / Empty / Error is received within the delay window, Loading is skipped completely—preventing skeleton flashes on 100ms-level fast requests.

### Minimum Loading Duration (`ssl_minLoadingDuration`, Default 300ms)

Once Loading is actually rendered on screen, it is retained for at least this duration (time spent waiting for initial layout is excluded). Targets received within this window are buffered, and only the latest one is committed upon expiry; re-requesting Loading during the wait cancels exit and continues the current Loading (start time and animation are not reset).

### Latest State Priority and Idempotency

- Submitting Content → Empty → Error consecutively during the exit-wait window renders only Error in the end, never flashing intermediate states.
- Re-submitting the same state is a no-op: does not rebuild Views, restart animations, or reset timers.
- When a new request arrives, previous transition animations are canceled and normalized to the currently committed frame, keeping only the latest state.
- `CROSSFADE` animates the library-owned wrapper container; once the animation begins, touch, focus, and accessibility events on the old view are intercepted, while business child attributes are preserved.
- All delayed tasks carry generation tokens; even if an abnormally dispatched legacy task fires, it cannot overwrite newer states.

### detach / attach

- **detach**: Stops all animations, unregisters layout/scroll listeners, releases masks, and cancels delayed tasks. Subsequent new requests only validate resource declarations and record the final intent without creating state Views or Handler tasks.
- **Aggregate invisibility**: Stops animations and geometry listeners while preserving masks. The coordinator continues its current timer, verifying geometry and resuming once visible again.
- **attach**: When rendered state lags behind the latest request, it reschedules according to the latest request (timers restart; old minimum loading duration countdowns do not resume after process death).
- **Device rotation**: `SavedState` restores requested state, state layout resources, skeleton configs, node rules, retry parameters, and instance state descriptions; listeners or business data are not saved. Restoration has no transition animation, and Loading recalculates timing from actual commitment.
- A new state subsequently submitted by `ViewModel` takes higher precedence.

## XML Attributes Reference

Consistent with `stateskeletonlayout/src/main/res/values/attrs.xml`.
All attributes use the `ssl_` prefix. When attached, configuration is pre-checked at the entry point; after detachment, only resource types are validated, and creation/binding errors surface upon re-attachment.
Priority: Built-in < Theme Defaults < Global Config < XML Explicit Style < XML Direct Attributes < Runtime Setters.

| Attribute | Format / Enum | Default | Precondition / Error Behavior |
| --- | --- | --- | --- |
| `ssl_initialState` | `content`, `loading`, `empty`, `error` | `content` | Initial request enters real rendering pipeline; rendered advances only after skeleton is ready |
| `ssl_emptyLayout` | reference | None (falls back to the built-in empty page) | Uses the built-in fallback page when unconfigured, no exception; with `useBuiltInStateLayouts=false` it throws, naming the attribute |
| `ssl_errorLayout` | reference | None (falls back to the built-in error page) | Uses the built-in fallback page when unconfigured, with its retry button auto-adopted as the unified retry entry point |
| `ssl_loadingLayout` | reference | None | Warns and falls back to skeleton if unconfigured when `useSkeleton=false` (default) |
| `ssl_useSkeleton` | boolean | `false` | `true` = skeleton, `false` = custom layout |
| `ssl_skeletonSource` | `content`, `template` | `content` | — |
| `ssl_skeletonTemplate` | reference | None | Throws exception if `template` lacks template or template has no shapes |
| `ssl_skeletonEffect` | `solid`, `shimmer`, `pulse` | `shimmer` | Mutually exclusive |
| `ssl_maskColor` | color | `@color/ssl_mask_default` | Skeleton base color |
| `ssl_highlightColor` | color | `@color/ssl_highlight_default` | Used only by shimmer |
| `ssl_cornerRadius` | dimension | `8dp` | Must not be negative |
| `ssl_animationDuration` | integer (ms) | `1200` | Must be greater than 0; separate concept from minimum loading duration |
| `ssl_shimmerDirection` | `start_to_end`, `end_to_start` | `start_to_end` | Respects RTL, no hardcoded left/right |
| `ssl_shimmerAngle` | integer (degrees) | `0` | Normalized to `[0, 359]` |
| `ssl_pulseMinAlpha` | float | `0.35` | `[0, 1]` and must not exceed max alpha |
| `ssl_pulseMaxAlpha` | float | `1.0` | `[0, 1]` |
| `ssl_loadingShowDelay` | integer (ms) | `0` | Must not be negative |
| `ssl_minLoadingDuration` | integer (ms) | `300` | Must not be negative |
| `ssl_transitionEffect` | `none`, `crossfade` | `crossfade` | Treated as `none` when system animations are disabled |
| `ssl_transitionDuration` | integer (ms) | `180` | Must not be negative |
| `ssl_errorRetryViewId` | reference | None | Points to internal View in error layout; warns if not found |
| `ssl_announceStateChanges` | boolean | `true` | Announces once when entering Loading/Empty/Error |

A default style `@style/ssl_StateSkeletonLayout` (consistent with the defaults above) is also provided to reduce repetitive declarations. Note: The `declare-styleable` is named `SslStateSkeletonLayout` (due to `resourcePrefix` requiring the `ssl_` prefix on library resources), while attribute names at XML call sites remain unchanged.

Theme Attribute `ssl_stateSkeletonLayoutStyle` points to a Style as a helper layer, which serves as the default `defStyleAttr` in the widget constructor; if unconfigured, this layer is skipped.

## Kotlin API Cheat Sheet

```kotlin
// Global defaults (process-level, merged once during construction)
StateSkeletonLayoutDefaults.install(SslGlobalDefaults(...))
StateSkeletonLayoutDefaults.reset()       // For test isolation

// Instance-level batch configuration (programmatic creation path; equivalent to
// individual setters, null fields are skipped)
layout.applyOptions(SslLayoutOptions(emptyLayoutResId = ..., errorLayoutResId = ...))
SslLayoutOptions.skeleton(emptyLayoutResId, errorLayoutResId)  // Convenience factory for the common combo

val requestedState: StateLayoutState      // Latest requested state (read-only)
val renderedState: StateLayoutState       // Currently rendered state (read-only)
var useSkeleton: Boolean                // true=skeleton, false=custom layout, default false
var skeletonConfig: SkeletonConfig        // Replaced as a whole
var loadingShowDelayMillis: Long
var minimumLoadingDurationMillis: Long
var transitionEffect: StateTransitionEffect
var transitionDurationMillis: Long
var announceStateChanges: Boolean

fun render(state: StateLayoutState)       // Sole state entry point (Main Thread)
fun showLoading() / showContent() / showEmpty() / showError()  // All delegate to render
fun invalidateSkeleton()                  // Manually refresh mask (Main Thread)
fun setStateViewLayout(state, layoutResId) // Change state layout (CONTENT not supported)
var errorRetryViewId: Int
var retryClickThrottleMillis: Long       // Default 500ms, can be 0
var retryClicksEnabled: Boolean
fun setOnRetryClickListener(listener)     // One-way retry event
fun setOnStateViewCreatedListener(listener)
fun setOnRenderedStateChangedListener(listener)
fun setOnLoadingVisibilityChangedListener(listener)
fun setOnStateViewReleasedListener(listener)
fun getStateView(state): View?            // Query cache only; skeleton returns decorative layer
fun updateStateView(state, update)         // Explicit create / bind, does not switch state
fun clearStateViewCache(state)             // Non-current states only, and never a View mid-transition
fun setSkeletonNodeConfig(viewId, config)  // null removes rule
fun setStateDescription(state, description)
fun diagnostics(): StateLayoutDiagnostics  // Includes the inStateViewTransaction flag
```

Public default-value constants (the single source of truth, kept in sync with the XML attribute table):
`DEFAULT_TRANSITION_DURATION_MILLIS`, `DEFAULT_RETRY_CLICK_THROTTLE_MILLIS`,
`DEFAULT_LOADING_SHOW_DELAY_MILLIS`, `DEFAULT_MINIMUM_LOADING_DURATION_MILLIS`.

The runtime setters, state layout configurations, and listener registrations above are UI APIs and must only be called on the main thread; calling from a background thread yields a consistent `IllegalStateException`.

## Accessibility and Performance

### Accessibility

- The skeleton mask is a decorative layer: no description and excluded from focus order, but intercepts touch events.
- When Loading is displayed, business content is temporarily hidden from screen readers and restored upon returning to Content state.
- Switching between Empty / Error / Content keeps only the active state accessible.
- API 30+ uses `stateDescription` and polite live regions to convey states; API 26–29 retains one-shot announcements. Instances can override state descriptions; duplicate submissions do not re-announce, and hidden views do not announce.
- Default strings provide Chinese and English resources; default skeleton colors provide `values-night`. After day/night changes, recreate instances or use `SkeletonConfig.from(context)` to read new resources and replace the configuration as a whole; explicit Global / XML / setter colors maintain their override semantics.
- Error page retry view has an effective touch target of no less than `48dp × 48dp` (satisfied by sample layouts).
- States are differentiated by more than just color; when system animations are off, static skeleton/state pages are shown without leaving blank areas.

### Performance

- Each skeleton widget stably holds a single `ALPHA_8` mask; during safe replacement, old and new masks briefly coexist before releasing the old one. Animation frames only update matrix/alpha and invalidate.
- No View tree traversal or object allocation in `onDraw` and animation callbacks; geometry is recalculated only upon layout/configuration invalidation.
- Changes to effect, color, direction, angle, or duration only update Paint/Shader without rebuilding the mask Bitmap.
- If Bitmap allocation fails, the old mask is retained where possible; if no old mask exists, a static solid color is displayed without misreporting memory exhaustion as a template error.
- State pages are created on demand; animations stop when invisible, and coordinator delayed tasks are canceled only upon detach. Non-current state caches can be explicitly released.
- States, timers, Bitmaps, and listeners of multiple widget instances are completely isolated; global default configuration is read-only and merged once upon construction, forming no mutable global state.
- `maximumMaskPixels` allows setting a pixel budget, unlimited by default. Exceeding the budget or allocation failure triggers static fallback; `diagnostics()` queries actual allocated bytes, rebuild attempts, fallback reasons, and animation status on demand without automated logging or uploading.

## FAQ

1. **Why is no skeleton visible after requesting Loading?**
   First check if a loading show delay is configured (requests completing within the delay window skip Loading); next verify whether content has valid dimensions (content-based extraction requires content to be laid out); when `useSkeleton=false`, missing a custom Loading layout logs a warning and falls back to skeleton. Use `diagnostics()` to check the current fallback reason and mask dimensions.
2. **Why does content-based shape extraction not produce expected shapes?**
   It only collects visible leaf Views in the business subtree: GONE / INVISIBLE business nodes, `Space`, and zero-width/height Views are skipped;
   During Loading, internal `ContentHost` is INVISIBLE yet continues collecting descendant layout changes in local coordinates;
   Nodes can be excluded by ID, configured as circles, or given custom corner radii; complex lists should use standalone templates. Translation, scrolling, and ancestor rectangular clipping are supported; arbitrary rotation, scale, clipPath, or line-by-line text extraction are not handled.
   If modifying `translationX/Y` or `clipBounds` without triggering layout/scroll events, call `invalidateSkeleton()` to update shapes; the library does not scan View trees frame by frame.
3. **What if the initial content height is 0?**
   Use template mode (`ssl_skeletonSource="template"`); content-based extraction will degrade to a solid static mask and log a warning in Debug.
4. **Why doesn't RecyclerView replace the Adapter?**
   Replacing Adapters disrupts scroll positions, state restoration, and business Adapter lifecycles; the initial release only supports template skeletons with enough static rows to cover the first viewport.
5. **How to play a full loop of Lottie / Shimmer?**
   Configure `ssl_minLoadingDuration` to match the animation's single-loop duration (Lottie starts/stops via visibility callbacks; the library does not rely on Animator frame callbacks for state decisions).
6. **How to disable transition animations?**
   Set `ssl_transitionEffect="none"` (or `transitionEffect = NONE` at runtime); automatically treated as NONE when system animations are turned off.
7. **How to change state layouts at runtime?**
   Call `setStateViewLayout(EMPTY, R.layout.…)`; the old View is removed and re-inflated upon next rendering; if currently rendering that state, it is immediately rebuilt without transition animations.
8. **What happens when I request a state page I never configured?**
   By default it **falls back to the library's built-in page** (centered text; the error page carries its own retry button) and no longer throws. The built-in pages are deliberately minimal, so if you have your own design spec, override them via `ssl_emptyLayout` / `ssl_errorLayout`. If you would rather have missing configuration **fail fast**, install `SslGlobalDefaults(useBuiltInStateLayouts = false)` — the exception message then names the missing attribute (e.g. `ssl_emptyLayout`).
9. **Why does Android Studio report "Can't find module entity for StateSkeletonLayout.StateSkeletonLayout" after module migration?**
   This indicates the IDE is still using legacy module identifiers when importing the project structure.
   Reopen the project, run `File > Sync Project with Gradle Files`, and select the `sample` run configuration once synchronization completes.
   Command-line build success does not replace IDE sync results; clean and rebuild if the issue persists.

## Edge-to-Edge System Bars in the Sample

`MainActivity` calls `enableEdgeToEdge()`, with system bar icon contrast following the day/night theme.
Landscape rendering can extend into display cutouts; content padding is handled by
`sample/src/main/java/com/sfyc/demo/insets/DemoInsets.kt`.

- The catalog applies padding only to `demoHomeContent`; each detail Fragment handles its own root View to avoid applying Insets twice.
- `applyDemoSystemBarPadding()` preserves the original XML padding and adds `systemBars` and `displayCutout` Insets. Repeated dispatches always calculate from the initial padding.
- Top, bottom, and side padding can be disabled independently. The listener returns the original Insets without consuming them for descendants.
- The network sample handles padding at its outer root only; SmartRefreshLayout and StateSkeletonLayout do not apply it again internally.
- This change is limited to `sample`. The demo screens have no input fields, so IME Insets are not handled.

`DemoInsetsTest` covers original padding and direction flags. On-device checks should cover portrait and landscape,
display cutouts, gesture and three-button navigation, and day/night themes, checking that titles, state controls, and lists remain clear of system bars.

## Project Structure and Testing

```text
StateSkeletonLayout/                    # Workspace directory retained; Gradle root project named stateskeletonlayout
├─ settings.gradle.kts / build.gradle.kts / gradle.properties
├─ gradle/libs.versions.toml            # Pinned versions, no dynamic versions
├─ gradle/verification-metadata.xml     # SHA-256 verification for every dependency
├─ stateskeletonlayout/                 # :stateskeletonlayout (Library)
│  ├─ api/stateskeletonlayout.api       # Public API baseline (checked by apiCheck)
│  ├─ consumer-rules.pro                # Intentionally empty, see below
│  ├─ src/main/java/com/sfyc/ssl/
│  │  ├─ StateSkeletonLayout.kt         # Sole public container
│  │  ├─ SslLayoutOptions.kt            # Instance-level batch configuration snapshot
│  │  ├─ StateLayoutState / SkeletonSource /
│  │  │   SkeletonEffect / SkeletonDirection / SkeletonConfig /
│  │  │   StateTransitionEffect / SkeletonNodeConfig / 4 listeners / diagnostic snapshot
│  │  └─ internal/                      # Coordinator/Registry/Renderer/Mask/Clock (Internal)
│  │     ├─ TransitionController.kt     # State transition animation (incl. cancellation semantics)
│  │     ├─ SslDefaultValues.kt         # Single source of truth for defaults
│  │     └─ skeleton/SkeletonTicker.kt  # Process-level shared animation clock
│  ├─ src/main/res/values/             # attrs/colors/dimens/strings/styles/ids
│  ├─ src/main/res/layout/             # Built-in empty / error fallback layouts
│  ├─ src/debug/res/layout/            # Test fixtures (not packaged into Release AAR)
│  ├─ src/test/                        # JVM: timing + config + default consistency + SavedState round-trip + screenshot regression
│  ├─ src/test/screenshots/            # Roborazzi screenshot baselines (must be committed)
│  └─ src/androidTest/                 # Device: Switching/touch/accessibility/detach restoration
└─ sample/                              # :sample (20 demo screens, source package com.sfyc.demo)
```

```powershell
# JVM Unit Tests (including Robolectric View / drawing and optimization regression tests)
.\gradlew.bat :stateskeletonlayout:testDebugUnitTest
# Lint Error enforcement / Assemble / Release / R8 consumer verification
.\gradlew.bat :stateskeletonlayout:lintRelease
.\gradlew.bat :stateskeletonlayout:assembleDebug
.\gradlew.bat :stateskeletonlayout:assembleRelease
.\gradlew.bat :stateskeletonlayout:assembleDebugAndroidTest
.\gradlew.bat :sample:testDebugUnitTest
.\gradlew.bat :sample:lintDebug
.\gradlew.bat :sample:lintRelease
.\gradlew.bat :sample:assembleDebug
.\gradlew.bat :sample:assembleRelease
.\gradlew.bat :sample:assembleDebugAndroidTest
# When a device is connected:
.\gradlew.bat :stateskeletonlayout:connectedDebugAndroidTest
.\gradlew.bat :sample:connectedDebugAndroidTest
```

The sample directory closes details via the Fragment back stack. Upon predictive back commitment or cancellation, `MainActivity` synchronizes detail container visibility on the next main-thread loop;
when the back stack is empty, the detail container must be `GONE` to avoid its full-screen background obscuring the catalog.
`MainActivityNavigationTest` covers standard back, predictive back, canceled back, and foreground/background switching;
`DemoSmokeTest` covers all sample back, canceled back, and re-entry after recreation on device.

```powershell
# Main navigation regression
.\gradlew.bat :sample:testDebugUnitTest --tests com.sfyc.demo.MainActivityNavigationTest
.\gradlew.bat :sample:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.sfyc.demo.DemoSmokeTest
```

Module and package name migration verification round (2026-10-01):

- 127 library and 36 sample JVM / Robolectric tests (total 163) passed with 0 failures, errors, or skips.
- Library Release Lint: 0 Errors / 16 Warnings; sample Debug / Release Lint: both 0 Errors / 53 Warnings.
  Warning counts and issue categories for library Release and sample Debug match pre-migration levels.
- Both modules assembled Debug / Release and AndroidTest APKs successfully; sample Release completed R8 shrinking; standard Release APK remains unsigned.
- Public AAR classes, ViewBinding, six Epoxy generated models, and sample / test Manifests were verified with new package names.
- Device tests were attempted and reported `No online devices found` due to unauthorized ADB or unavailable emulators, and were not executed.
  There were 15 demo screens at that point (later 16, currently 20); rotation recovery, R8 Release startup, etc., still require device follow-up.

Historical records below represent pre-migration states; legacy task names and artifact paths are retained as-is and do not serve as proof for the current verification round.

Prior optimization verification (2026-10-01, before adding network sample & module migration):

- Library JVM / Robolectric: 127 passed, 0 failures, 0 errors, 0 skips; includes key regressions for API 26 / 34.
- Library Release Lint: 0 Errors / 13 Warnings; demo Debug Lint: 0 Errors / 45 Warnings. Retained version updates, sample styles, and copy prompts without upgrading dependencies or globally suppressing warnings.
- Library Release AAR, demo Debug / Release, and both modules' AndroidTest APKs assembled successfully; demo Release passed R8 processing. Release APK unsigned.
- Connected M2004J7AC (Android 12 / API 31) rejected installation: `INSTALL_FAILED_USER_RESTRICTED`. Device tests were not executed; APK assembly does not constitute verification pass.
- TalkBack, varying screen widths / fonts, layout editor, and long-term memory scenarios still require manual review.

Historical verification (2026-09-22, before module migration, device PDVM00, Android 11; not current verification proof):

- JVM unit tests 72/72 passed (including coordinator transactions, shimmer directions, mask/OOM, dynamic geometry, state recovery, and transition cancellation).
- Device `:StateSkeletonLayout` 5/5 passed (including Shimmer adjacent frame pixel verification), `:demo` smoke 1/1 passed.
- Dual-module Lint 0 Errors; Release AAR and R8-shrunk demo Release assembled successfully.
- Library dependency tree verified to contain only AndroidX Annotation; Gradle Wrapper and dependencies enforce SHA-256 verification; CI executes unit tests, Lint, Release, and dual-module AndroidTest APK assembly.

Items not covered by automated tests requiring manual verification:

- Demo layouts across 360dp / 375dp / 393dp / 411dp widths, portrait/landscape, and extra-large fonts.
- Complete TalkBack workflows (announcements, focus isolation, retry reachability).
- Skeleton and transition behavior under developer options animator scales (0x / 0.5x / 1x).
- Rapid click feel on state buttons and retry buttons.
- Linear / Grid list template alignment and scrolling on physical devices.

## Network Multi-Type Sample

Open "Network Multi-Type" from the sample home page. The refresh container combines SmartRefreshLayout → StateSkeletonLayout → EpoxyRecyclerView: the first viewport uses a template skeleton, while pull-to-refresh and load-more retain existing content.
Networking uses local `delay` simulations without requiring backend servers, internet permissions, or image loading libraries.

- Six models: Banner, Section, Profile, Article, Tile, and Horizontal Recommendation Row; default pagination 10 / 10 / 7, totaling 27 business records and 30 presentation models.
- Nine deterministic scenarios: Normal 3 pages, initial empty, initial failure once, initial persistent failure, refresh empty, refresh failure, page 2 failure once, initial no more data, empty final page. Initial one-time failure recovers via error retry; replaying resets faults.
- Control panel allows selecting 100 / 500 / 1500 ms delays, toggling Linear / 2-column Grid, replaying, or loading next page. Changing delay affects subsequent requests; switching layout sends no requests; only Tile occupies half-span in Grid.
- Rotation retains ViewModel requests and data, restoring outer and horizontal scroll positions after model submission; process death restores only small parameters and re-requests the first page.
  Failed refresh or load-more retains data and cursors; successful refresh returning empty enters Empty state.
- Only `:sample` integrates SmartRefreshLayout 3.0.0-alpha, Epoxy 5.2.1, KSP 2.2.10-2.0.2, and Coroutines 1.10.2. Library module APIs, source files, and dependencies remain unchanged.

Windows cross-drive builds verified: with Gradle cache on drive F and project on drive G, KSP2 incremental dependency path tracking fails, so `gradle.properties` sets `ksp.incremental=false`. Kotlin plugin remains 2.2.10; sample runtime standard library resolves to 2.2.21, and processor configuration resolves to 2.3.0-Beta1 (not included in APK).

Network sample pre-migration verification (2026-10-01):

- Demo JVM / Robolectric 36 tests and library regression 127 tests (total 163) passed with 0 failures / errors / skips.
- Demo Debug Lint: 0 Errors / 53 Warnings; library Release Lint: 0 Errors / 16 Warnings. 8 new Overdraw hints correspond to nested cards, horizontal items, and day/night backgrounds, without globally suppressing warnings.
- Debug, R8 Release, dual AndroidTest APKs, and library Release AAR built with standard dependency verification; 112 new SHA-256 attachments match official repository bytes.
- Local debug-keystore-signed `build/network-implementation/demo-release-validation.apk` passed v2 / v3 verification for local R8 validation only.
- M2004J7AC (Android 12 / API 31) rejected installation `INSTALL_FAILED_USER_RESTRICTED`; device tests not executed; Release execution, gestures, TalkBack, and screen width matrix require manual testing.

## Release Process

### About `consumer-rules.pro` (R8 / Obfuscation)

`stateskeletonlayout/consumer-rules.pro` is passed through to the host with the Release AAR. It contains
**only comments and no keep rules at all**, and that is deliberate:

- Custom Views used from XML (`<com.sfyc.ssl.StateSkeletonLayout>`) get precise keep rules generated
  automatically by the Android Gradle Plugin — none need to be written by hand.
- Public APIs called directly from Kotlin / Java are retained by R8's reachability analysis.
- Wildcard rules like `public *` would keep a large amount of code that is actually never used, masking
  real shrinking problems. The `:sample` Release build with `minifyEnabled = true` serves as the
  consumer-side regression.

**Exception**: if your project uses this widget **only via reflection or dynamic class names** (a
configuration-driven page framework, for instance), AGP cannot infer the reference and you need to add
rules yourself:

```proguard
-keep class com.sfyc.ssl.StateSkeletonLayout { public *; }
-keep class com.sfyc.ssl.SkeletonConfig { public *; }
-keep class com.sfyc.ssl.SslLayoutOptions { public *; }
```

### Release Steps

The version number is centrally maintained in `gradle.properties` under `libraryVersion`; both library POM and sample app read the same value.
Current version is `0.0.3`, and Git tags also use `0.0.3` (without `v` prefix).

1. After updating the version, run library and sample tests, Lint, Release builds, and verify local Maven publication:

   ```powershell
   .\gradlew.bat :stateskeletonlayout:testDebugUnitTest :stateskeletonlayout:verifyRoborazziDebug :stateskeletonlayout:apiCheck :stateskeletonlayout:lintRelease :stateskeletonlayout:publishReleasePublicationToMavenLocal :sample:testDebugUnitTest :sample:lintRelease :sample:assembleRelease
   ```

2. Commit and push source code, then create and push a version tag matching `libraryVersion`:

   ```powershell
   git push origin main
   git tag -a 0.0.3 -m "Release 0.0.3"
   git push origin 0.0.3
   ```

3. Request a build on the JitPack project page and inspect its status and logs. Once it succeeds, verify the POM, AAR, source jar, and API documentation jar, then verify dependency resolution and builds in a consumer project before creating the GitHub Release for the tag.

JitPack runs Java 17 and invokes the library Maven publication task via `jitpack.yml` in the root directory.
Publications use Maven POM and standard `-sources.jar`; to maintain compatibility with JitPack's source file handling, Gradle `.module` metadata is not published.
Plugins and dependencies prioritize resolution from Google, Maven Central, and Gradle Plugin Portal, with Aliyun mirrors as fallbacks; dependency versions and SHA-256 verifications remain pinned.
The GitHub repository includes the library, sample, tests, Gradle Wrapper, and CI; local SDK configurations, build artifacts, caches, signing keys, and internal `docs/` are excluded from version control.
Published tags must remain immutable; subsequent changes must use a new version number.

## Limitations (Not Included in Initial Release)

- Jetpack Compose bindings / APIs.
- Automatic network requests / automatic ViewModel binding.
- Arbitrary number of business states and dynamic state registration.
- List skeletons replacing Adapters; ViewPager2 skeletons.
- Node-level XML attributes, arbitrary Paths, line-by-line text shape extraction, rotation and scale geometry.
- Public custom LoadingRenderer plugin interfaces.
- Maven Central publication pipeline, image loading, backend error models.

## References and License

- Thanks to [liangjingkanji/StateLayout](https://github.com/liangjingkanji/StateLayout) and [Faltenreich/SkeletonLayout](https://github.com/Faltenreich/SkeletonLayout) for inspiring this project's design.
- The library module explicitly depends on AndroidX Annotation (Apache-2.0) and the Kotlin standard library (Apache-2.0); the sample module additionally uses AppCompat / Material / RecyclerView / Lifecycle / Lottie / SVGAPlayer-Android / SmartRefreshLayout / Epoxy / Coroutines, etc., all Apache-2.0 (Checker Qual is MIT), for demonstration purposes only.
- This project is licensed under the Apache License 2.0; see `LICENSE` in the root directory.
