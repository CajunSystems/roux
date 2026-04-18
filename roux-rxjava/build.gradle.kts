plugins {
    `java-library`
    `maven-publish`
    signing
}

repositories {
    mavenCentral()
}

group = "com.cajunsystems"
version = "0.3.0"

dependencies {
    api(project(":lib"))
    api(libs.rxjava)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withJavadocJar()
    withSourcesJar()
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("roux-rxjava")
}

tasks.named<Jar>("sourcesJar") {
    archiveBaseName.set("roux-rxjava")
}

tasks.named<Jar>("javadocJar") {
    archiveBaseName.set("roux-rxjava")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("--enable-preview")
}

tasks.withType<Javadoc> {
    options {
        this as StandardJavadocDocletOptions
        addStringOption("Xdoclint:none", "-quiet")
        addBooleanOption("-enable-preview", true)
        addStringOption("-release", "21")
    }
}

tasks.withType<Test> {
    jvmArgs("--enable-preview")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = "com.cajunsystems"
            artifactId = "roux-rxjava"

            pom {
                name.set("Roux RxJava")
                description.set("RxJava 3 (Single/Observable) integration for the Roux effect system")
                url.set("https://github.com/CajunSystems/roux")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("cajunsystems")
                        name.set("CajunSystems")
                        email.set("pradeep.samuel90@gmail.com")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/CajunSystems/roux.git")
                    developerConnection.set("scm:git:ssh://github.com:CajunSystems/roux.git")
                    url.set("https://github.com/CajunSystems/roux")
                }
            }
        }
    }

    repositories {
        maven {
            name = "CentralPortal"
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
}

val hasSigningCredentials = project.hasProperty("signing.keyId") &&
        project.hasProperty("signing.password") &&
        project.hasProperty("signing.secretKeyRingFile")

signing {
    setRequired {
        gradle.taskGraph.hasTask("publishMavenJavaPublicationToCentralPortalRepository") && hasSigningCredentials
    }

    if (hasSigningCredentials) {
        sign(publishing.publications["mavenJava"])
    } else {
        logger.lifecycle("Signing disabled for ${project.path} - missing signing properties")
    }
}

tasks.register<Zip>("createCentralBundle") {
    from(layout.buildDirectory.dir("repo"))
    archiveFileName.set("roux-rxjava-${version}-bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    dependsOn("publishMavenJavaPublicationToCentralPortalRepository")
}
