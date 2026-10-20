pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.application") {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
}
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven("https://jitpack.io") } }
rootProject.name = "AmbientFlow"
include(":app")
