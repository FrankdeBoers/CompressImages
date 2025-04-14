package com.frank.compressimage.setting

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.frank.compressimage.R
import com.frank.compressimage.databinding.ActivityOtherBinding
import com.frank.compressimage.model.Constants.UI_MODEL
import com.frank.compressimage.model.UiModel
import com.google.gson.Gson

class OtherSetting : AppCompatActivity() {

    private val logTag = "FrankCompress# OtherSetting#"

    private lateinit var binding: ActivityOtherBinding

    // 各种UI参数
    private var uiModel = UiModel()

    private val sp by lazy {
        getSharedPreferences("sp_main", Context.MODE_PRIVATE)
    }

    private val editor by lazy {
        sp.edit()
    }

    private val gson by lazy { Gson() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityOtherBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        initParams()
        initView()
    }

    private fun initParams() {
        kotlin.runCatching {
            uiModel = gson.fromJson(sp.getString(UI_MODEL, ""), UiModel::class.java)
        }.onFailure {
            Log.e("", "$logTag refreshParams failed", it)
        }
    }

    private fun initView() {
        binding.deleteSrcFile.setOnCheckedChangeListener { _, isChecked ->
            uiModel.isDeleteSrcFile = isChecked
            syncSp()
        }
        // 删除源文件，保持上次选择的开关
        binding.deleteSrcFile.isChecked = uiModel.isDeleteSrcFile

        binding.replaceCheckBox.setOnCheckedChangeListener { _, isChecked ->
            uiModel.replaceIfExist = isChecked
            syncSp()
        }
        // 覆盖同名文件，保持上次选择的开关
        binding.replaceCheckBox.isChecked = uiModel.replaceIfExist

        binding.keepModifyTime.setOnCheckedChangeListener { _, isChecked ->
            uiModel.isKeepModifyTime = isChecked
            syncSp()
        }
        // 保持文件修改时间
        binding.keepModifyTime.isChecked = uiModel.isKeepModifyTime
    }

    private fun syncSp() {
        val json = gson.toJson(uiModel)
        editor.putString(UI_MODEL, json)
        // 提交修改
        editor.apply() // 异步提交，无返回值
    }
}