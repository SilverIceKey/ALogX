package com.sik.alogx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.sik.alogx.ui.theme.ALogXTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    /**
     * Android 6 ~ 10：请求外置存储读写权限
     */
    private val storagePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val granted =
                (result[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true) ||
                        (result[Manifest.permission.READ_EXTERNAL_STORAGE] == true)

            if (granted) {
                initLogIfNeeded()
            } else {
                // TODO: 这里你要不要提示看你自己
            }
        }

    /**
     * Android 11+：跳转到“管理所有文件”权限设置页
     */
    private val manageAllFilesLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager()
            ) {
                initLogIfNeeded()
            } else {
                // TODO: 用户没给权限，你自己看要不要提示
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 先搞权限 + 初始化日志框架
        checkPermissionAndInitLog()

        enableEdgeToEdge()
        setContent {
            ALogXTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    /**
     * 根据不同 Android 版本，检查并申请相应的存储权限。
     */
    private fun checkPermissionAndInitLog() {
        when {
            // Android 11 及以上：需要 MANAGE_EXTERNAL_STORAGE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    initLogIfNeeded()
                } else {
                    openAllFilesAccessSetting()
                }
            }

            // Android 6 ~ 10：运行时申请 READ/WRITE_EXTERNAL_STORAGE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                val writeGranted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED

                val readGranted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED

                if (writeGranted || readGranted) {
                    initLogIfNeeded()
                } else {
                    storagePermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        )
                    )
                }
            }

            // Android 6 以下：没有运行时权限机制，直接初始化
            else -> {
                initLogIfNeeded()
            }
        }
    }

    /**
     * 打开系统“管理所有文件”设置页，让用户手动勾选。
     */
    private fun openAllFilesAccessSetting() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        try {
            // 打开当前应用的专属权限设置
            val uri = Uri.parse("package:$packageName")
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
            manageAllFilesLauncher.launch(intent)
        } catch (e: Exception) {
            // 某些 ROM 不支持上面的 action，就退而求其次打开总开关页面
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            manageAllFilesLauncher.launch(intent)
        }
    }

    /**
     * 初始化 ALogX（你之后可以挪到 Application 里）
     */
    private fun initLogIfNeeded() {
        LogCenter.init(
            applicationContext,
            LogConfig(
                appName = "ALogX",   // 日志根目录：/sdcard/ALogX/logs
                maxKeepDays = 7,
                enableLogcat = true
            )
        )
    }

    override fun onResume() {
        super.onResume()
        initLogIfNeeded()
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Column {
        Text(
            text = "Hello $name!",
            modifier = modifier
        )

        Button(onClick = {

            // 1. 基础等级
            ALog.v("MainActivity", "Verbose 日志")
            ALog.d("MainActivity", "Debug 日志")
            ALog.i("MainActivity", "Info 日志")
            ALog.w("MainActivity", "Warn 日志")
            ALog.e("MainActivity", "Error 日志")
            ALog.wtf("MainActivity", "WTF 日志")

            // 2. 自动 TAG
            ALog.d("这是自动 TAG 日志")

            // 3. JSON 格式化
            ALog.json("JsonTest", """{"name":"ALogX","state":"good","time":123456}""")

            // 4. HEX 输出
            ALog.hex("HexTest", byteArrayOf(0x00, 0x01, 0x7F, 0xFF.toByte()))

            // 5. Long Log 分段输出
            val longMsg = buildString {
                repeat(5000) { append("X") }
            }
            ALog.long("LongTest", longMsg)
            ALog.blob("image", "Image1".toByteArray())

            // 6. 压测
            repeat(50) {
                ALog.d("Stress", "压测日志 #$it")
            }

        }) {
            Text("输出日志")
        }

        Button(onClick = {
            ALog.meminfo("MemInfo")
        }) {
            Text("Dump 内存")
        }
    }
}


@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    ALogXTheme {
        Greeting("Android")
    }
}
