{
  description = "Android Jetpack Compose development environment with Kotlin";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
  }:
    flake-utils.lib.eachDefaultSystem (system: let
      pkgs = import nixpkgs {
        inherit system;
        config = {
          android_sdk.accept_license = true;
          allowUnfree = true;
        };
      };

      buildToolsVersion = "34.0.0";
      androidComposition = pkgs.androidenv.composeAndroidPackages {
        buildToolsVersions = [buildToolsVersion "30.0.3"];
        platformVersions = ["34" "33"];
        abiVersions = ["arm64-v8a" "x86_64"];
        includeEmulator = false;
        includeSystemImages = false;
        includeSources = false;
        includeNDK = false;
        useGoogleAPIs = false;
        extraLicenses = [
          "android-sdk-license"
          "android-sdk-preview-license"
        ];
      };

      androidSdk = androidComposition.androidsdk;
    in {
      devShells.default = pkgs.mkShell {
        buildInputs = with pkgs; [
          # Java Development Kit
          jdk17

          # Android SDK
          androidSdk

          # Kotlin
          kotlin
          kotlin-language-server

          # Gradle
          gradle

          # Useful tools
          ktlint
          android-tools # adb, fastboot
        ];

        shellHook = ''
          export ANDROID_HOME="${androidSdk}/libexec/android-sdk"
          export ANDROID_SDK_ROOT="$ANDROID_HOME"
          export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/tools:$ANDROID_HOME/tools/bin:$PATH"

          # Java home
          export JAVA_HOME="${pkgs.jdk17}"

          echo "Android Jetpack Compose Development Environment"
          echo "================================================"
          echo "Java:    $(java -version 2>&1 | head -n 1)"
          echo "Kotlin:  $(kotlin -version 2>&1)"
          echo "Gradle:  $(gradle --version | grep Gradle | head -n 1)"
          echo "Android: $ANDROID_HOME"
          echo ""
          echo "Commands:"
          echo "  ./gradlew build          - Build the project"
          echo "  ./gradlew assembleDebug  - Build debug APK"
          echo "  ./gradlew installDebug   - Install on device"
          echo "  adb devices              - List connected devices"
        '';

        # Fix for gradle daemon
        GRADLE_OPTS = "-Dorg.gradle.daemon=false";
      };
    });
}
