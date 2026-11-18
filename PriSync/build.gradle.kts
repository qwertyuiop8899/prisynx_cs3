// PriSync: only the PriSynx encrypted cloud sync (same sources as the hub's sync tab).
android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared/src/main/kotlin"))
    sourceSets.getByName("main").java.srcDir(rootProject.file("PriSynx/src/main/kotlin/prisynx/sync"))
}

version = 2

cloudstream {
    description = "PriSync: salva e ripristina con un PIN tutta la configurazione di CloudStream (repository, plugin, impostazioni dei plugin, cronologia, preferiti). I plugin mancanti vengono riscaricati in automatico al riavvio."
    authors = listOf("PriSynx")
    status = 1
    tvTypes = listOf("Others")
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/qwertyuiop8899/prisynx_cs3/main/assets/logos/PriSynx.png"
}

dependencies {
    "cloudstream"("com.lagradost:cloudstream3:pre-release")
}
