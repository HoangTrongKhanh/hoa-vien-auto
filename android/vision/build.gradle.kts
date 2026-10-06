// Nhận diện màn hình game bằng OpenCV, viết thuần Kotlin/JVM để kiểm thử được trên máy tính.
// Trong app, các lớp org.opencv.* lấy từ thư viện OpenCV cho Android; ở đây chỉ dùng bản máy tính
// (org.openpnp:opencv, cùng API) để biên dịch và chạy kiểm thử.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly("org.openpnp:opencv:4.9.0-0")
    testImplementation("org.openpnp:opencv:4.9.0-0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("hoavien.root", rootDir.parentFile.absolutePath)    // thư mục chứa templates/ và samples/
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}
