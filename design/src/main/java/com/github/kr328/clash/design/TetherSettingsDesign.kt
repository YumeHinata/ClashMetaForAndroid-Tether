package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignTetherSettingsBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.google.android.material.switchmaterial.SwitchMaterial

class TetherSettingsDesign(context: Context) : Design<TetherSettingsDesign.Request>(context) {
    enum class Request {
        ToggleTether, ToggleAutoStart,
    }

    private val binding = DesignTetherSettingsBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
        
        // Set click listener for the start tether button
        binding.startTetherButton.setOnClickListener {
            requests.trySend(Request.ToggleTether)
        }
    }

    fun request(request: Request) {
        requests.trySend(request)
    }

    fun setupAutoStartSwitch(isEnabled: Boolean, switchView: SwitchMaterial, onToggle: (Boolean) -> Unit) {
        switchView.isChecked = isEnabled
        switchView.setOnCheckedChangeListener { _, isChecked ->
            onToggle(isChecked)
            requests.trySend(Request.ToggleAutoStart)
        }
    }

    fun setToggleText(text: String) {
        binding.startTetherButton.text = text
    }

    fun setToggleSubtext(subtext: String) {
        binding.startTetherButton.subtext = subtext
    }

    fun setToggleEnabled(enabled: Boolean) {
        binding.startTetherButton.isEnabled = enabled
    }
}
