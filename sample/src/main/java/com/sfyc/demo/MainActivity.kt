package com.sfyc.demo

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.sfyc.demo.insets.applyDemoSystemBarPadding
import com.sfyc.demo.ssl.R
import com.sfyc.demo.ssl.databinding.ActivityMainBinding

/**
 * 示例首页：RecyclerView 展示示例列表，点击进入对应 Fragment。
 *
 * 详情页以 replace + 回退栈方式覆盖在列表之上，返回即回到列表。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        // DayNight 图标对比由 enableEdgeToEdge 的 auto 样式跟随主题，不手写明暗判断。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // 横屏允许绘制进刘海区，内容避让由各页根 View 的 cutout insets 承担。
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 只垫首页内容区；详情容器由各 Fragment 根 View 自己垫，避免双重叠加。
        binding.demoHomeContent.applyDemoSystemBarPadding()

        binding.demoList.layoutManager = LinearLayoutManager(this)
        binding.demoList.adapter = MainDemoAdapter(DemoCatalog.entries) { entry ->
            openDemo(entry)
        }
        supportFragmentManager.addOnBackStackChangedListener(
            object : FragmentManager.OnBackStackChangedListener {
                override fun onBackStackChanged() {
                    updateDetailVisibility()
                }

                override fun onBackStackChangeCommitted(fragment: Fragment, pop: Boolean) {
                    if (pop) {
                        // 提交回调触发时返回事务尚未清理，下一轮主线程读取最终回退栈。
                        binding.demoDetailContainer.post { updateDetailVisibility() }
                    }
                }

                override fun onBackStackChangeCancelled() {
                    // 取消后等待 FragmentManager 完成栈恢复。
                    binding.demoDetailContainer.post { updateDetailVisibility() }
                }
            }
        )
        // 配置变化后 FragmentManager 会先恢复回退栈，首帧必须与恢复结果一致。
        updateDetailVisibility()
    }

    private fun openDemo(entry: DemoCatalog.Entry) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.demo_detail_container, entry.factory())
            .addToBackStack(entry.title)
            .commit()
        binding.demoDetailContainer.visibility = View.VISIBLE
    }

    private fun updateDetailVisibility() {
        binding.demoDetailContainer.visibility =
            if (supportFragmentManager.backStackEntryCount > 0) View.VISIBLE else View.GONE
    }
}
