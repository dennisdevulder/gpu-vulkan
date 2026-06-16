/*
 * rlmtl — minimal native bridge that attaches a CAMetalLayer to the AWT
 * canvas's region on macOS so MoltenVK has a valid surface to render into.
 *
 * Approach: walk JAWT to reach the canvas's CALayer + the NSWindow's
 * contentView (AWTView, the single NSView holding all of RuneLite's UI on
 * macOS JDK). Assign a CAMetalLayer through JAWT_SurfaceLayers so AWT owns
 * the layer placement. Drive AppKit's compose tick with a main-run-loop
 * NSTimer + [CATransaction flush] so MoltenVK's async drawable presents
 * actually reach the screen.
 *
 * Build (vkdev does this automatically):
 *   clang -fno-objc-arc -shared -dynamiclib \
 *       -framework Cocoa -framework QuartzCore -framework Metal \
 *       -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/darwin" \
 *       -Wl,-undefined,dynamic_lookup \
 *       rlmtl.m -o librlmtl.dylib
 */

#import <jawt.h>
#import <jawt_md.h>
#import <QuartzCore/QuartzCore.h>
#import <AppKit/AppKit.h>
#import <Metal/Metal.h>

static CAMetalLayer* gMetalLayer = nil;
static NSTimer* gFlushTimer = nil;

/* JAWT_SurfaceLayers integration: the protocol has a `layer` SETTER —
 * the application assigns its CALayer; AWT then incorporates that layer
 * into the Canvas's CALayer hierarchy and handles positioning + resize
 * automatically. This is the documented pattern (same one rlawt uses for
 * OpenGL). Reading `layer` before setting returns nil, which is what
 * tripped the earlier "use surfaceLayers.layer" attempt.
 *
 * With this approach our CAMetalLayer is geometrically scoped to the AWT
 * Canvas widget's bounds — anything outside the Canvas (RuneLite plugin
 * panels rendered as Swing siblings, the title bar) is not covered. We
 * don't need a parent-layer reference to track resizes; AWT does it. The
 * display link only needs to keep CAMetalLayer.drawableSize in sync with
 * the layer's bounds (CAMetalLayer doesn't auto-update drawableSize), and
 * to flush CATransaction so MoltenVK's async-presented drawables actually
 * reach the compositor each refresh. */
@interface DisplayLinkTicker : NSObject
- (void)displayLinkTick:(NSTimer*)timer;
@end

@implementation DisplayLinkTicker
- (void)displayLinkTick:(NSTimer*)timer
{
    CAMetalLayer* mtl = gMetalLayer;
    if (mtl) {
        /* Keep drawableSize in sync with the layer's actual on-screen
         * size. AWT moves/resizes our layer (we set it via
         * surfaceLayers.layer = mtl), so layer.bounds reflects the
         * current AWT Canvas size in points. Multiply by contentsScale
         * for the pixel-resolution backing texture. */
        CGFloat scale = mtl.contentsScale;
        if (scale <= 0) scale = 1;
        CGSize wanted = CGSizeMake(
            mtl.bounds.size.width  * scale,
            mtl.bounds.size.height * scale);
        if (wanted.width > 0 && wanted.height > 0
            && !CGSizeEqualToSize(wanted, mtl.drawableSize))
        {
            [CATransaction begin];
            [CATransaction setDisableActions: YES];
            mtl.drawableSize = wanted;
            [CATransaction commit];
        }
    }
    /* Force any pending implicit CATransaction (from MoltenVK's
     * [drawable present] earlier this frame) to commit so the layer
     * actually shows the latest drawable on this refresh. */
    [CATransaction flush];
}
@end

static DisplayLinkTicker* gTicker = nil;

static void rlmtlFlushCoreAnimationAsync(void)
{
    dispatch_async(dispatch_get_main_queue(), ^{
        [CATransaction flush];
    });
}

static void rlmtlStopDisplayLink(void)
{
    if (gFlushTimer) {
        [gFlushTimer invalidate];
        [gFlushTimer release];
        gFlushTimer = nil;
    }
}

static void rlmtlDetachMetalLayer(void)
{
    rlmtlStopDisplayLink();
    CAMetalLayer* layer = gMetalLayer;
    gMetalLayer = nil;
    DisplayLinkTicker* ticker = gTicker;
    gTicker = nil;
    if (!layer && !ticker) {
        return;
    }
    void (^cleanup)(void) = ^{
        if (layer) {
            /* AWT may still hold a reference via surfaceLayers.layer.
             * removeFromSuperlayer detaches us from whatever AWT-managed
             * parent layer we ended up under; our explicit -release drops
             * the retain we held in nAttachMetalLayer. AWT's retain (set
             * via the property assignment) is dropped when AWT tears down
             * the surfaceLayers object — usually on canvas dispose. */
            [layer removeFromSuperlayer];
            [layer release];
        }
        if (ticker) {
            [ticker release];
        }
    };
    if ([NSThread isMainThread]) {
        cleanup();
    } else {
        dispatch_sync(dispatch_get_main_queue(), cleanup);
    }
}

JNIEXPORT void JNICALL
Java_com_gpuvulkan_MacOSMetalHelper_nDetachMetalLayer(
    JNIEnv* env, jclass cls)
{
    rlmtlDetachMetalLayer();
}

JNIEXPORT void JNICALL
Java_com_gpuvulkan_MacOSMetalHelper_nResizeMetalLayer(
    JNIEnv* env, jclass cls, jint widthPoints, jint heightPoints, jdouble scaleHint)
{
    CAMetalLayer* layer = gMetalLayer;
    if (!layer) {
        return;
    }
    int w = widthPoints > 0 ? widthPoints : 1;
    int h = heightPoints > 0 ? heightPoints : 1;
    void (^resize)(void) = ^{
        CGFloat scale = scaleHint > 0 ? (CGFloat) scaleHint : layer.contentsScale;
        if (scale <= 0) {
            scale = [[NSScreen mainScreen] backingScaleFactor];
            if (scale <= 0) scale = 1;
        }
        layer.contentsScale = scale;

        [CATransaction begin];
        [CATransaction setDisableActions: YES];
        layer.contentsScale = scale;
        CGSize bounds = layer.bounds.size;
        CGFloat drawableWidth = bounds.width > 0 ? bounds.width : (CGFloat) w;
        CGFloat drawableHeight = bounds.height > 0 ? bounds.height : (CGFloat) h;
        layer.drawableSize = CGSizeMake(drawableWidth * scale, drawableHeight * scale);
        [CATransaction commit];
        [CATransaction flush];
    };
    if ([NSThread isMainThread]) {
        resize();
    } else {
        dispatch_async(dispatch_get_main_queue(), resize);
    }
}

/*
 * Retain/release an arbitrary Objective-C object pointer (used for keeping
 * MTLTexture handles alive while MetalDrawableSet caches VkImages wrapping
 * them — see MetalDrawableSet.java for why).
 *
 * The CAMetalDrawable's MTLTexture is owned by the drawable. Once the
 * drawable's retain count drops to zero (typically after the present
 * MTLCommandBuffer completes asynchronously), the texture is released and
 * any VkImage we built around it goes dangling. CAMetalLayer's drawable
 * pool may then hand back a drawable whose `texture` pointer reuses the
 * freed slot — our cache lookup by pointer thinks it's a hit, returns the
 * stale VkImage, and MoltenVK crashes inside command encoding the next
 * time it dereferences the dead MTLTexture.
 *
 * Workaround: pin the MTLTexture with our own retain for as long as we
 * keep the VkImage cached. Drop the retain when the cache entry is
 * evicted (canvas resize) or the renderer is closed.
 */
JNIEXPORT void JNICALL
Java_com_gpuvulkan_MacOSMetalHelper_nRetainObject(
    JNIEnv* env, jclass cls, jlong ptr)
{
    id obj = (id)(uintptr_t) ptr;
    if (obj) [obj retain];
}

JNIEXPORT void JNICALL
Java_com_gpuvulkan_MacOSMetalHelper_nReleaseObject(
    JNIEnv* env, jclass cls, jlong ptr)
{
    id obj = (id)(uintptr_t) ptr;
    if (obj) [obj release];
}

/*
 * Custom present path — bypasses MoltenVK's vkQueuePresentKHR entirely.
 *
 * The idea: MoltenVK's swapchain + present is one of the few code paths we
 * can't see into. Sync validation is silent on the symptom, the geometry
 * renders correctly, but a "one layer covering/uncovering" flicker persists
 * tied to zoom level. The likeliest remaining suspect is the timing of the
 * internal [drawable present] call MoltenVK schedules in response to
 * vkQueuePresentKHR — its async present queue can hand a drawable to the
 * compositor at a point that doesn't agree with where our render commands
 * land in the Metal command stream.
 *
 * By acquiring the CAMetalDrawable directly from our CAMetalLayer, importing
 * its MTLTexture as a VkImage on the Java side (via VK_EXT_metal_objects),
 * rendering into that VkImage, and then presenting it via our OWN tiny
 * MTLCommandBuffer ordered after the render submit, we get full ordering
 * control. No async present queue between us and the compositor.
 *
 * Returns the drawable + its texture handles packed into a Java long[]:
 *   [0] = (uintptr_t)id<CAMetalDrawable>   (retained — caller must
 *                                            eventually release via Present)
 *   [1] = (uintptr_t)id<MTLTexture>
 *   [2] = (jlong)drawable.texture.width    (pixels)
 *   [3] = (jlong)drawable.texture.height   (pixels)
 * Returns NULL if the layer is unavailable or nextDrawable blocks past the
 * 1-second internal timeout.
 */
JNIEXPORT jlongArray JNICALL
Java_com_gpuvulkan_MacOSMetalHelper_nNextDrawable(
    JNIEnv* env, jclass cls)
{
    CAMetalLayer* layer = gMetalLayer;
    if (!layer) {
        return NULL;
    }

    /* @autoreleasepool is MANDATORY here. The JVM's JNI threads don't have
     * a top-level autorelease pool, so every autoreleased Objective-C object
     * that AppKit/Metal create during -[CAMetalLayer nextDrawable] (and the
     * deeper compositor calls it makes) would leak forever — including big
     * IOSurfaces and MoltenVK-internal Metal state — until MoltenVK trips
     * on its own debris ~50 frames in and crashes inside its compositor
     * code. Wrap every JNI entry that calls into AppKit/Metal in a pool. */
    __block jlongArray result = NULL;
    @autoreleasepool {
        /* nextDrawable can block waiting for a drawable to become available
         * (CAMetalLayer.maximumDrawableCount defaults to 3 on macOS). Call
         * on the calling JNI thread (the RuneLite render thread). Not main
         * thread — we don't want to stall the AppKit event loop. */
        id<CAMetalDrawable> drawable = [[layer nextDrawable] retain];
        if (!drawable) {
            return NULL;
        }

        id<MTLTexture> tex = drawable.texture;
        if (!tex) {
            [drawable release];
            return NULL;
        }

        jlong values[4];
        values[0] = (jlong)(uintptr_t) drawable;
        values[1] = (jlong)(uintptr_t) tex;
        values[2] = (jlong) tex.width;
        values[3] = (jlong) tex.height;

        result = (*env)->NewLongArray(env, 4);
        if (!result) {
            [drawable release];
            return NULL;
        }
        (*env)->SetLongArrayRegion(env, result, 0, 4, values);
    }
    return result;
}

/*
 * Schedules [drawable present] on a tiny MTLCommandBuffer built from the
 * supplied MTLCommandQueue, then releases the retain we took in
 * nNextDrawable. The Vulkan render submit must have committed (and ideally
 * completed; the caller waits on its fence/semaphore) BEFORE this call —
 * otherwise we'd present a half-rendered drawable.
 */
JNIEXPORT void JNICALL
Java_com_gpuvulkan_MacOSMetalHelper_nPresentDrawable(
    JNIEnv* env, jclass cls, jlong drawableHandle, jlong mtlQueueHandle)
{
    id<CAMetalDrawable> drawable = (id<CAMetalDrawable>) (uintptr_t) drawableHandle;
    id<MTLCommandQueue> queue = (id<MTLCommandQueue>) (uintptr_t) mtlQueueHandle;
    if (!drawable || !queue) {
        if (drawable) [drawable release];
        return;
    }

    /* @autoreleasepool is MANDATORY (see comment in nNextDrawable). The
     * MTLCommandBuffer returned by -[MTLCommandQueue commandBuffer] is
     * autoreleased, as are the assorted Metal objects MoltenVK and the
     * compositor create during the present path. Without a pool every
     * frame these accumulate and either OOM or trip MoltenVK's internal
     * resource tracking — manifesting as a SIGSEGV inside libMoltenVK
     * a few dozen frames in. */
    @autoreleasepool {
        id<MTLCommandBuffer> cmd = [queue commandBuffer];
        [cmd presentDrawable: drawable];
        [cmd addCompletedHandler:^(id<MTLCommandBuffer> completed) {
            (void) completed;
            rlmtlFlushCoreAnimationAsync();
        }];
        [cmd commit];
        /* Do NOT wait — Metal handles the present asynchronously on the
         * compositor side. Ordering with our Vulkan render is guaranteed
         * by both submits running on the same MTLCommandQueue. */
        [drawable release];
    }
}

JNIEXPORT jlong JNICALL
Java_com_gpuvulkan_MacOSMetalHelper_nAttachMetalLayer(
    JNIEnv* env, jclass cls, jobject canvas, jboolean vsync,
    jint initialWidthPoints, jint initialHeightPoints, jdouble scaleHint)
{
    rlmtlDetachMetalLayer();

    JAWT awt;
    const jint versions[] = {
        JAWT_VERSION_9 | JAWT_MACOSX_USE_CALAYER,
        JAWT_VERSION_1_7 | JAWT_MACOSX_USE_CALAYER,
        JAWT_VERSION_1_4 | JAWT_MACOSX_USE_CALAYER,
    };
    jboolean got = JNI_FALSE;
    for (int i = 0; i < 3; i++) {
        awt.version = versions[i];
        if (JAWT_GetAWT(env, &awt)) {
            got = JNI_TRUE;
            break;
        }
    }
    if (!got) {
        return 0;
    }

    JAWT_DrawingSurface* ds = awt.GetDrawingSurface(env, canvas);
    if (!ds) {
        return 0;
    }

    jint lock = ds->Lock(ds);
    if ((lock & JAWT_LOCK_ERROR) != 0) {
        awt.FreeDrawingSurface(ds);
        return 0;
    }

    /* Retain surfaceLayers under the JAWT lock, then release the lock
     * before AppKit work. surfaceLayers is the JAWT bridge object whose
     * `layer` property we'll assign our CAMetalLayer to on the main
     * thread. */
    id<JAWT_SurfaceLayers> surfaceLayers = nil;
    JAWT_DrawingSurfaceInfo* dsi = ds->GetDrawingSurfaceInfo(ds);
    if (dsi) {
        id<JAWT_SurfaceLayers> sl =
            (id<JAWT_SurfaceLayers>) dsi->platformInfo;
        if (sl) {
            surfaceLayers = (id<JAWT_SurfaceLayers>) [(NSObject*) sl retain];
        }
        ds->FreeDrawingSurfaceInfo(dsi);
    }
    ds->Unlock(ds);
    awt.FreeDrawingSurface(ds);

    if (!surfaceLayers) {
        return 0;
    }

    __block CAMetalLayer* metalLayer = nil;
    __block CGFloat scale = scaleHint > 0 ? (CGFloat) scaleHint : 1;
    dispatch_sync(dispatch_get_main_queue(), ^{
        if (scale <= 0) {
            scale = [[NSScreen mainScreen] backingScaleFactor];
            if (scale <= 0) scale = 1;
        }

        metalLayer = [[CAMetalLayer alloc] init];
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (device) {
            metalLayer.device = device;
            [device release];
        }
        metalLayer.pixelFormat = MTLPixelFormatBGRA8Unorm;
        metalLayer.allowsNextDrawableTimeout = YES;
        metalLayer.contentsScale = scale;
        metalLayer.anchorPoint = CGPointZero;
        metalLayer.position = CGPointZero;
        metalLayer.frame = CGRectMake(0, 0,
                                      initialWidthPoints > 0 ? initialWidthPoints : 1,
                                      initialHeightPoints > 0 ? initialHeightPoints : 1);
        /* drawableSize MUST start small here because vkCreateSwapchainKHR
         * later reads surfaceCapabilities.currentExtent, which MoltenVK
         * derives from drawableSize. A full-size value here makes
         * MoltenVK's internal swapchain claim drawables from the same
         * 3-deep pool we acquire from directly — they collide on the
         * first scene render and SIGSEGV inside libMoltenVK at a fixed
         * offset. 1×1 keeps the swapchain a tiny no-op (we never use
         * it on macOS anyway; our custom-present path bypasses it). */
        metalLayer.drawableSize = CGSizeMake(1, 1);
        /* displaySyncEnabled hardcoded to NO. The vsync param is currently
         * ignored on macOS — see the comment below. The compositor still
         * presents at most one drawable per display refresh, so visible
         * FPS is capped at refresh rate (120 Hz on ProMotion) regardless.
         *
         * Why we don't honor vsync=YES yet: setting displaySyncEnabled=YES
         * combined with our custom-present path (where the [drawable
         * present] runs on a separate MTLCommandBuffer from the Vulkan
         * render submit) reliably crashes MoltenVK on the first scene
         * render at a fixed offset (libMoltenVK+0x47a78). Reverting to
         * NO is the known-good configuration. If the user really wants a
         * true vsync mode (battery-saver, no tearing), the right
         * approach is probably to route present back through
         * vkQueuePresentKHR conditionally — that's a bigger change,
         * deferred. */
        (void) vsync;
        metalLayer.displaySyncEnabled = NO;
        surfaceLayers.layer = metalLayer;
    });
    (void) initialWidthPoints;
    (void) initialHeightPoints;

    /* AWT retains the metal layer via its property setter (the setter is
     * declared `retain`). Our retain on surfaceLayers was only to keep
     * the bridge object alive across the dispatch_sync — drop it now. */
    [(NSObject*) surfaceLayers release];

    if (!metalLayer) {
        return 0;
    }

    NSLog(@"rlmtl: attached CAMetalLayer %p via JAWT_SurfaceLayers @ %.1fx scale",
        metalLayer, scale);

    gMetalLayer = metalLayer;

    /* AppKit doesn't tick a CAMetalLayer's compose loop on its own when
     * MoltenVK presents drawables asynchronously — without the periodic
     * flush in the tick handler, drawables get queued but never displayed.
     * NSTimer keeps this bridge compatible with pre-Sonoma macOS; exact
     * display cadence is not required because Vulkan still drives frames. */
    dispatch_sync(dispatch_get_main_queue(), ^{
        gTicker = [[DisplayLinkTicker alloc] init];
        gFlushTimer = [[NSTimer timerWithTimeInterval: (1.0 / 120.0)
                                               target: gTicker
                                             selector: @selector(displayLinkTick:)
                                             userInfo: nil
                                              repeats: YES] retain];
        [[NSRunLoop mainRunLoop] addTimer: gFlushTimer forMode: NSRunLoopCommonModes];
    });

    return (jlong)(uintptr_t) metalLayer;
}
