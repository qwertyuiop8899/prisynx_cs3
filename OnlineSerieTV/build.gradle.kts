android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

version = 3

cloudstream {
    description = "OnlineSerieTV: film e serie TV WordPress via MaxStream."
    authors = listOf("PriSynx")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/qwertyuiop8899/prisynx_cs3/main/assets/logos/OnlineSerieTV.png"
}