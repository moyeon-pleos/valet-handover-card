package dev.pleos3.car

import android.util.Log

object CarLog {
    const val TAG = "PLEOS3"

    fun i(msg: String) {
        // 방송에 여러 줄이 들어와도 로그 한 줄로 남긴다.
        Log.i(TAG, msg.replace("\r", "\\r").replace("\n", "\\n"))
    }
}
