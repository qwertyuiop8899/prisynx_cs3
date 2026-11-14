repositories {
    // App classes (Plugin/openSettings) are not in the library AAR: pull the
    // official pre-release classes.jar as compileOnly, never packaged.
    ivy {
        url = uri("https://github.com/recloudstream/cloudstream/releases/download/pre-release")
        patternLayout { artifact("[artifact].[ext]") }
        metadataSources { artifact() }
        content { includeModule("cloudstream", "classes") }
    }
}

android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

version = 4

dependencies {
    compileOnly("cloudstream:classes:pre-release@jar")
}

cloudstream {
    description = "AnimeUnity: anime sub ITA e ITA con mapping TMDB, sezioni della home e filtri locali."
    authors = listOf("PriSynx")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/qwertyuiop8899/prisynx_cs3/main/assets/logos/AnimeUnity.png"
}
