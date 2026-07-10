plugins {
  `java-library`
  `maven-publish`
}

dependencies {
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
  useJUnitPlatform()
}

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      from(components["java"])
      artifactId = "scheduler-bridge-common"
    }
  }
}
