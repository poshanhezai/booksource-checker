package com.shuyuan.helper.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuyuan.helper.R
import com.shuyuan.helper.data.AppLog
import com.shuyuan.helper.databinding.ActivityLogBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    private val saveLog = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val content = AppLog.read(this)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    true
                }.getOrDefault(false)
            }
            toast(if (ok) "日志已保存" else "保存失败，请重试")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogBack.setOnClickListener { finish() }
        binding.btnLogSave.setOnClickListener {
            if (AppLog.read(this).isBlank()) {
                toast("当前没有可保存的日志")
                return@setOnClickListener
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            saveLog.launch("书源助手日志_$stamp.txt")
        }
        binding.btnLogClear.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("清空日志")
                .setMessage("确定清空所有运行日志吗？此操作不可撤销。")
                .setPositiveButton("清空") { _, _ ->
                    AppLog.clear(this)
                    AppLog.append(this, AppLog.Tag.APP, "运行日志已清空")
                    refresh()
                    toast(getString(R.string.log_cleared))
                }
                .setNegativeButton("取消", null)
                .show()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val content = AppLog.read(this)
        binding.tvLogContent.text = content.ifBlank { getString(R.string.log_empty) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
