package com.sfyc.demo

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
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
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.demoList.layoutManager = LinearLayoutManager(this)
        binding.demoList.adapter = MainDemoAdapter(DemoCatalog.entries) { entry ->
            openDemo(entry)
        }
        supportFragmentManager.addOnBackStackChangedListener {
            updateDetailVisibility()
        }
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
