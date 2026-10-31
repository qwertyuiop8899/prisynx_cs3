// PriSynx hub: one extension bundling every PriSynx source (TMDB based
// providers plus the native KissKH catalogue). The provider sources of the
// other modules are copied in (without their *Plugin.kt entry points) so
// there is a single @CloudstreamPlugin class.
val hubSourceDir = layout.buildDirectory.dir("generated/hubsrc").get().asFile

val syncHubSources = tasks.register<Sync>("syncHubSources") {
    from(rootDir) {
        include("*/src/main/kotlin/**/*.kt")
        exclude("*/src/main/kotlin/**/*Plugin.kt")
        exclude("PriSynx/**", "shared/**")
    }
    into(hubSourceDir)
}

android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared/src/main/kotlin"))
    sourceSets.getByName("main").java.srcDir(hubSourceDir)
}

tasks.named("preBuild").configure { dependsOn(syncHubSources) }

version = 15

cloudstream {
    description = "PriSynx: tutte le sorgenti in un'unica estensione (RaiPlay e KissKH inclusi), con menu TV per scegliere provider, ordine, cataloghi TMDB, TopX, anime, RaiPlay, KissKH e servizi debrid (con attesa del download) per IlCorsaroViola."
    authors = listOf("PriSynx")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "AsianDrama", "Live")
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/qwertyuiop8899/prisynx_cs3/main/assets/logos/PriSynx.png"
}

dependencies {
    // Stubs of the CloudStream app (android Plugin class with openSettings).
    "cloudstream"("com.lagradost:cloudstream3:pre-release")
}
