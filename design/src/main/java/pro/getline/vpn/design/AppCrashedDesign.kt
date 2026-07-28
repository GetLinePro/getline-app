package pro.getline.vpn.design

import android.content.Context
import android.view.View
import pro.getline.vpn.design.databinding.DesignAppCrashedBinding
import pro.getline.vpn.design.util.applyFrom
import pro.getline.vpn.design.util.bindAppBarElevation
import pro.getline.vpn.design.util.layoutInflater
import pro.getline.vpn.design.util.root

class AppCrashedDesign(context: Context) : Design<Unit>(context) {
    private val binding = DesignAppCrashedBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    fun setAppLogs(logs: String) {
        binding.logsView.text = logs
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
    }
}