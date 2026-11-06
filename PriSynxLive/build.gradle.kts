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

version = 2

cloudstream {
    description = "PriSynx Live: TV in diretta Vavoo (nazioni selezionabili, EPG Italia) ed eventi sportivi live SportzX (F1, MotoGP, calcio, NBA, NHL, UFC...), con impostazioni per nazioni, sport e canali DRM."
    authors = listOf("PriSynx")
    status = 1
    tvTypes = listOf("Live")
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/qwertyuiop8899/prisynx_cs3/main/assets/logos/VavooLive.png"
}

dependencies {
    "cloudstream"("com.lagradost:cloudstream3:pre-release")
}
