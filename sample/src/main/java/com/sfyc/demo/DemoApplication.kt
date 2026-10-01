package com.sfyc.demo

import android.app.Application
import com.sfyc.demo.defaults.DemoSslDefaults

/**
 * 示例应用 Application：安装示例基准全局默认，演示宿主真实接入形态。
 */
class DemoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        DemoSslDefaults.installCanonical()
    }
}
