// PriSynx Live: Vavoo TV + SportzX live sports in one extension with its own
// settings. Vavoo sources are copied from the VavooLive module (without its plugin).
val vavooSrc = layout.buildDirectory.dir("generated/vavoosrc").get().asFile

val syncVavoo = tasks.register<Sync>("syncVavoo") {
    from(rootProject.file("VavooLive/src/main/kotlin")) {
        include("**/*.kt")
        exclude("**/*Plugin.kt")
    }
    into(vavooSrc)
}

android {
    sourceSets.getByName("main").java.srcDir(vavooSrc)
}

tasks.named("preBuild").configure { dependsOn(syncVavoo) }

version = 8

cloudstream {
    description = "PriSynx Live: TV in diretta Vavoo (nazioni selezionabili, EPG Italia), HTSport (canali Sky/DAZN IT e sport live con decodifica segmenti integrata), SportzX e Partite.cc."
    authors = listOf("PriSynx")
    status = 1
    tvTypes = listOf("Live")
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/qwertyuiop8899/prisynx_cs3/main/assets/logos/VavooLive.png"
}

dependencies {
    "cloudstream"("com.lagradost:cloudstream3:pre-release")
}
