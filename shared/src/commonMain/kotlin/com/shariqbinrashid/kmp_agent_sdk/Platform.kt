package com.shariqbinrashid.kmp_agent_sdk

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform