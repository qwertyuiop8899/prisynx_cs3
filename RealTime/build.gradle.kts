android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

version = 3

cloudstream {
    description = "Real Time: programmi e serie TV del canale Real Time."
    authors = listOf("PriSynx")
    status = 1
    tvTypes = listOf("TvSeries", "Live", "Movie")
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/qwertyuiop8899/prisynx_cs3/main/assets/logos/RealTime.png"
}