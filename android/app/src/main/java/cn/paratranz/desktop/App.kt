package cn.paratranz.desktop

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        NotifyService.createChannels(this)
    }
}
