pluginManagement {
	repositories {
		google {
			content {
				includeGroupByRegex("com\\.android.*")
				includeGroupByRegex("com\\.google.*")
				includeGroupByRegex("androidx.*")
			}
		}
		mavenCentral()
		gradlePluginPortal()
		maven(url = "https://maven.singular.net/")
	}
}
plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
	repositories {
		google()
		mavenCentral()
		maven(url = "https://maven.singular.net/")
		maven(url = "https://cboost.jfrog.io/artifactory/chartboost-ads/")
		maven(url = "https://android-sdk.is.com/")
		maven(url = "https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea")
		maven(url = "https://artifact.bytedance.com/repository/pangle")
	}
}

rootProject.name = "Mar2SDK"
include(":app")
include(":core")
include(":impl")
include(":debug")
