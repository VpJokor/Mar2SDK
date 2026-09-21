plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.parcelize)
}

version = "1.3.0"

android {
	namespace = "com.mar2sdk.core"
	buildFeatures {
		buildConfig = true
	}
	compileSdk {
		version = release(37) {
			minorApiLevel = 0
		}
	}

	defaultConfig {
		minSdk = 29
		buildConfigField("String", "SDK_VERSION", "\"${project.version}\"")
		consumerProguardFiles("consumer-rules.keep")

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}

}

dependencies {
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.core.ktx)
	implementation(libs.material)
	implementation(libs.kotlinx.coroutines.android)
	implementation("com.squareup.okhttp3:okhttp:4.12.0")
	testImplementation(libs.junit)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation("org.json:json:20250517")
	androidTestImplementation(libs.androidx.espresso.core)
	androidTestImplementation(libs.androidx.junit)
	//Google相关服务
	implementation(platform("com.google.firebase:firebase-bom:34.2.0"))
	implementation("com.google.firebase:firebase-analytics")
	implementation("com.google.firebase:firebase-config")
	implementation("com.google.firebase:firebase-crashlytics-ndk")
	implementation("com.google.firebase:firebase-messaging")
	implementation("com.google.android.play:integrity:1.6.0")
	implementation("com.google.android.gms:play-services-base:18.9.0")
	implementation("com.google.android.ump:user-messaging-platform:4.0.0")
	api("com.google.android.gms:play-services-ads:25.3.0")
	implementation("com.google.android.play:app-update:2.1.0")
	implementation("com.android.installreferrer:installreferrer:2.2")

	// AdMob mediation adapters
	implementation("com.google.ads.mediation:chartboost:9.10.2.0")
	implementation("com.google.ads.mediation:fyber:8.4.1.0")
	implementation("com.google.ads.mediation:inmobi:11.1.0.0")
	implementation("com.google.ads.mediation:ironsource:9.2.0.0")
	implementation("com.google.ads.mediation:vungle:7.6.1.0")
	implementation("com.google.ads.mediation:facebook:6.21.0.0")
	implementation("com.google.ads.mediation:mintegral:17.0.31.0")
	implementation("com.google.ads.mediation:pangle:8.0.0.4.0")
	implementation("com.unity3d.ads:unity-ads:4.16.2")
	implementation("com.google.ads.mediation:unity:4.16.4.0")

	//Singular依赖
	implementation("com.singular.sdk:singular_sdk:12.9.1")
	//数数依赖
	implementation("cn.thinkingdata.android:ThinkingAnalyticsSDK:3.0.3.1")

}
