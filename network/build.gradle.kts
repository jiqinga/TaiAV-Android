plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    // Android supplies org.json. JVM checks use the same API without bundling a second copy in the APK.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
}

val checkNetwork by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks DNS packets, destination validation, and real bidirectional CONNECT tunnels."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.taiav.network.NetworkChecksKt")
}
tasks.check { dependsOn(checkNetwork) }

tasks.register<JavaExec>("probeSite") {
    group = "verification"
    description = "Makes a certificate-verified HTTPS request through the actual local tunnel."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.taiav.network.SiteProbeKt")
}
