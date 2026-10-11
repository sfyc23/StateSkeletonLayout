package com.sfyc.demo.svga

import android.animation.ValueAnimator
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.SVGAParser
import com.opensource.svgaplayer.SVGAVideoEntity
import com.sfyc.demo.DemoLog
import com.sfyc.demo.insets.applyDemoSystemBarPadding
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.FragmentSvgaBinding
import com.sfyc.demo.ssl.databinding.StateLoadingSvgaBinding
import com.sfyc.ssl.StateLayoutState
import java.lang.ref.WeakReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 本地 SVGA 加载示例：状态控制显示，页面控制解析、播放许可和资源所有权。 */
class SvgaLoadingFragment : Fragment() {
    private var binding: FragmentSvgaBinding? = null
    private var loadingBinding: StateLoadingSvgaBinding? = null
    private var log: DemoLog? = null
    private var loadingVisible = false
    private var resourceState = ResourceState.IDLE
    private var parseGeneration = 0L
    private var loadingJob: Job? = null

    // 单一解析接缝供测试控制迟到回调；正式页面始终读取相同的本地资源。
    internal var parseAsset: (Context, SVGAParser.ParseCompletion) -> Unit = { context, completion ->
        SVGAParser(context).decodeFromAssets("loading.svga", completion)
    }

    // View 的 STARTED 事件发生在 Fragment.onStart 之后，避免恢复时过早判断生命周期。
    private val playbackObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = updatePlayback()
        override fun onStop(owner: LifecycleOwner) = updatePlayback()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val created = FragmentSvgaBinding.inflate(inflater, container, false)
        binding = created
        return created.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.applyDemoSystemBarPadding()
        val page = checkNotNull(binding)
        log = DemoLog(page.demoLog)
        val state = page.demoState
        state.setOnStateViewCreatedListener { _, created, createdView ->
            if (created == StateLayoutState.LOADING) bindLoadingView(createdView)
        }
        state.setOnRenderedStateChangedListener { _, old, new, _ ->
            log?.add("呈现：$old → $new")
            updateStatus()
        }
        state.setOnLoadingVisibilityChangedListener { _, visible, loadingView ->
            if (visible && loadingView != null) bindLoadingView(loadingView)
            loadingVisible = visible
            updatePlayback()
        }
        state.setOnStateViewReleasedListener { _, released, releasedView ->
            if (released == StateLayoutState.LOADING) releaseLoadingView(releasedView)
        }
        state.setOnRetryClickListener { simulateLoad() }
        state.getStateView(StateLayoutState.LOADING)?.let(::bindLoadingView)
        viewLifecycleOwner.lifecycle.addObserver(playbackObserver)

        page.demoBtnReplay.setOnClickListener { simulateLoad() }
        page.demoBtnLoading.setOnClickListener { startLoading() }
        page.demoBtnContent.setOnClickListener { showResult(StateLayoutState.CONTENT) }
        page.demoBtnEmpty.setOnClickListener { showResult(StateLayoutState.EMPTY) }
        page.demoBtnError.setOnClickListener { showResult(StateLayoutState.ERROR) }
        updateStatus()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // 恢复旧控件状态后再发起新演示，防止 SavedState 覆盖本次 Loading 请求。
        simulateLoad()
    }

    private fun bindLoadingView(view: View) {
        if (loadingBinding?.root === view) return
        loadingBinding?.root?.let(::releaseLoadingView)
        loadingBinding = StateLoadingSvgaBinding.bind(view)
        resourceState = ResourceState.IDLE
        log?.add("SVGA 加载布局已绑定")
        ensureParsed()
    }

    private fun ensureParsed(retry: Boolean = false) {
        val player = loadingBinding?.demoSvgaView ?: return
        if (resourceState != ResourceState.IDLE && !(retry && resourceState == ResourceState.FAILED)) return
        resourceState = ResourceState.PARSING
        val generation = ++parseGeneration
        log?.add("开始解析 loading.svga")
        updatePlayback()
        parseAsset(player.context.applicationContext, AssetCompletion(this, player, generation))
    }

    private fun acceptsResult(player: SVGAImageView, generation: Long): Boolean =
        binding != null && parseGeneration == generation &&
            loadingBinding?.demoSvgaView === player && resourceState == ResourceState.PARSING

    private fun onParsed(player: SVGAImageView, video: SVGAVideoEntity) {
        // 解码成功不等于参数可播放；上游用 Int 计算时长，需避免除零和溢出。
        val size = video.videoSize
        val valid = video.FPS in 1..60 && video.frames > 0 &&
            video.frames.toLong() * (1000 / video.FPS) <= Int.MAX_VALUE &&
            size.width > 0 && size.height > 0 && size.width.isFinite() && size.height.isFinite()
        if (!valid) {
            video.clear()
            onParseError()
            return
        }
        player.setVideoItem(video)
        resourceState = ResourceState.READY
        log?.add("解析成功：${video.frames} 帧，${video.FPS} FPS")
        updatePlayback()
    }

    private fun onParseError() {
        resourceState = ResourceState.FAILED
        log?.add("SVGA 解析失败，保留加载反馈；可重新加载")
        updatePlayback()
    }

    private fun updatePlayback() {
        if (binding == null) return
        val loading = loadingBinding
        val player = loading?.demoSvgaView
        val animationsEnabled = ValueAnimator.areAnimatorsEnabled()
        val started = viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val ready = resourceState == ResourceState.READY
        loading?.demoSvgaFrame?.visibility = if (animationsEnabled) View.VISIBLE else View.GONE
        player?.visibility = if (ready && animationsEnabled) View.VISIBLE else View.INVISIBLE
        val visible = loadingVisible && loading?.root?.isAttachedToWindow == true && loading.root.isShown
        val shouldPlay = ready && visible && started && animationsEnabled
        if (shouldPlay) {
            if (player != null && !player.isAnimating) {
                player.startAnimation()
                log?.add("SVGA 从首帧开始播放")
            }
        } else {
            stopPlayback()
        }
        loading?.demoSvgaProgress?.visibility =
            if (!ready && visible && started && animationsEnabled) View.VISIBLE else View.GONE
        updateStatus()
    }

    private fun stopPlayback() {
        loadingBinding?.demoSvgaView?.let { player ->
            if (player.isAnimating) {
                player.stopAnimation(false)
                log?.add("SVGA 已停止，保留实体供下次加载")
            }
        }
    }

    private fun updateStatus() {
        val page = binding ?: return
        val playback = when {
            !ValueAnimator.areAnimatorsEnabled() -> R.string.demo_svga_static
            loadingBinding?.demoSvgaView?.isAnimating == true -> R.string.demo_svga_playing
            else -> R.string.demo_svga_stopped
        }
        page.demoSvgaStatus.text = getString(
            R.string.demo_svga_status,
            getString(resourceState.label), getString(playback), page.demoState.renderedState.name,
        )
    }

    private fun startLoading() {
        loadingJob?.cancel()
        loadingJob = null
        stopPlayback()
        binding?.demoState?.showLoading()
        ensureParsed(retry = true)
        updatePlayback()
        log?.add("请求 LOADING（本地 SVGA）")
    }

    private fun simulateLoad() {
        startLoading()
        loadingJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(SIMULATED_LOAD_MILLIS)
            binding?.demoState?.showContent()
            log?.add("模拟请求完成，请求 CONTENT")
            loadingJob = null
        }
    }

    private fun showResult(state: StateLayoutState) {
        loadingJob?.cancel()
        loadingJob = null
        binding?.demoState?.render(state)
        log?.add("手动请求 $state，取消模拟任务")
    }

    private fun releaseLoadingView(view: View) {
        val player = view.findViewById<SVGAImageView>(R.id.demo_svga_view)
        player?.stopAnimation(false)
        player?.callback = null
        player?.clear()
        if (loadingBinding?.root === view) {
            ++parseGeneration
            loadingBinding = null
            resourceState = ResourceState.IDLE
            loadingVisible = false
        }
        log?.add("SVGA 加载布局资源已释放")
    }

    override fun onDestroyView() {
        ++parseGeneration
        loadingJob?.cancel()
        loadingJob = null
        loadingBinding?.root?.let(::releaseLoadingView)
        loadingVisible = false
        viewLifecycleOwner.lifecycle.removeObserver(playbackObserver)
        binding?.demoState?.apply {
            setOnLoadingVisibilityChangedListener(null)
            setOnStateViewCreatedListener(null)
            setOnStateViewReleasedListener(null)
            setOnRenderedStateChangedListener(null)
            setOnRetryClickListener(null)
        }
        binding = null
        log = null
        super.onDestroyView()
    }

    /** 嵌套类只保留弱引用；不可让线程池中的回调捕获 Fragment 或旧 Binding。 */
    private class AssetCompletion(fragment: SvgaLoadingFragment, player: SVGAImageView, private val generation: Long) :
        SVGAParser.ParseCompletion {
        private val fragmentRef = WeakReference(fragment)
        private val playerRef = WeakReference(player)

        override fun onComplete(videoItem: SVGAVideoEntity) {
            val fragment = fragmentRef.get()
            val player = playerRef.get()
            if (fragment != null && player != null && fragment.acceptsResult(player, generation)) {
                fragment.onParsed(player, videoItem)
            } else {
                videoItem.clear()
            }
        }

        override fun onError() {
            val fragment = fragmentRef.get() ?: return
            val player = playerRef.get() ?: return
            if (fragment.acceptsResult(player, generation)) fragment.onParseError()
        }
    }

    private enum class ResourceState(val label: Int) {
        IDLE(R.string.demo_svga_idle),
        PARSING(R.string.demo_svga_parsing),
        READY(R.string.demo_svga_ready),
        FAILED(R.string.demo_svga_failed),
    }

    private companion object {
        const val SIMULATED_LOAD_MILLIS = 7000L
    }
}
