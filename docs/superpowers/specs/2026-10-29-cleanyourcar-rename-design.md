# CleanYourCar Identity Rename

- **Intent:** Make the merged Android project consistently use the CleanYourCar identity instead of CarPlayLauncher.
- **Goals:** Rename the app package to `com.cleanyourcar.launcher`, the companion package to `com.cleanyourcar.navbridge`, and the displayed/root project name to `CleanYourCar`; update source paths, manifest identifiers, build/install scripts, tests, and documentation accordingly.
- **Non-goals:** Change launcher behavior, project layout, dependencies, the `.idea` directory, or Git state. Do not commit or push.
- **Constraints:** Keep Java and Android module behavior intact. Maintain the companion app's explicit-intent contract with the launcher. Keep existing preference-key values to avoid an unnecessary settings migration.
- **Approaches:** (A) Rename the full identity across packages and references (selected): consistent new app identity, but Android treats it as a different installed app. (B) Change only display/docs: lower risk but leaves the old package as the permanent technical identity. (C) Change only package IDs: technically consistent but user-visible text remains stale. Selected A because the project is moving into the CleanYourCar repository and should present that identity consistently.
- **Implementation calls:** Use `CleanYourCar` as the display and Gradle root name; preserve historical device/package identifiers when they refer to the car's stock software rather than this app; do not rename unrelated descriptive terms such as Google Maps or navigation bridge.
