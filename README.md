# **RTP (Random Teleport Plugin)**

<p>

  <!-- Build Status -->
  <a href="https://github.com/VikumKarunathilake/RTP/actions">
    <img src="https://github.com/VikumKarunathilake/RTP/actions/workflows/maven-publish.yml/badge.svg" alt="Build Status">
  </a>

  <!-- Latest Release -->
  <a href="https://github.com/VikumKarunathilake/RTP/releases/latest">
    <img src="https://img.shields.io/github/v/release/VikumKarunathilake/RTP?label=Latest%20Release" alt="Latest Release">
  </a>

  <!-- Downloads -->
  <a href="https://github.com/VikumKarunathilake/RTP/releases">
    <img src="https://img.shields.io/github/downloads/VikumKarunathilake/RTP/total?color=brightgreen&label=Downloads" alt="Total Downloads">
  </a>

  <!-- License -->
  <a href="https://github.com/VikumKarunathilake/RTP/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/VikumKarunathilake/RTP?color=blue&label=License" alt="License">
  </a>

</p>

---

*A maintained fork of the original RTP plugin by DailyStruggle*

This repository is an actively maintained fork of the original **RTP** plugin created by **[DailyStruggle](https://github.com/DailyStruggle)**. Our goals are straightforward:

* Maintain **buildability** across environments
* Ensure **compatibility** with modern server versions
* Provide **regular updates** (currently targets **Paper 1.21.8**)
* Deliver **stable, reliable releases** for the community

While the original project is no longer maintained, this fork ensures RTP continues to work reliably on current Minecraft platforms.

---

## **Current Status**

* ✅ Lightly tested on **Paper 1.21.8**
* ✅ Compatible with **Java 21**
* ✅ Produces fully shaded, dependency-free builds
* ✅ Automated releases via GitHub Actions on the `main` branch

---

## **Original Author**

Full credit for the original RTP plugin goes to:

➡️ **[DailyStruggle](https://github.com/DailyStruggle)**

This fork continues their work to ensure ongoing availability, stability, and usability.

---

## **About the Plugin**

RTP is a high-performance random teleportation plugin featuring:

* Mathematically consistent random distribution
* Intelligent learning algorithms to avoid invalid areas
* Support for **multiple RTP regions per world**
* Highly configurable world/region/permission systems
* Fully customizable messages
* Extensible architecture for biomes, shapes, protections, and more

This fork preserves the original core functionality while modernizing the build system and runtime environment.

---

## **Distribution Shapes**

The plugin includes several mathematically sophisticated distribution shapes:

### **Circle — Exponential Distribution (0.1, 1.0, 10.0)**
![zu5gW62](https://user-images.githubusercontent.com/28832622/210043913-fd624a9f-8bdd-45de-b877-6a5f5e3bf40a.png)

### **Square — Exponential Distribution (0.1, 1.0, 10.0)**
![3mrkKh1](https://user-images.githubusercontent.com/28832622/210043922-4d94e3d6-e829-4adc-a21a-74cce484f8e6.png)

### **Circle — Normal Distribution**
![SUGBQk3](https://user-images.githubusercontent.com/28832622/210043926-5c5013cf-032e-444c-9397-e381c17a4752.png)

### **Square — Normal Distribution**
![pzu9j63](https://user-images.githubusercontent.com/28832622/210043956-df964dde-4c70-460b-a377-ffd49a365e69.png)

### **Rectangle — Flat Distribution with Rotation**
![3Yw2tBj](https://user-images.githubusercontent.com/28832622/210043964-ca9725b8-be25-4e3c-a460-90f8b81326cb.png)

Additional shapes can be implemented through the plugin's extension API.

See the `/addons` directory for examples including custom shapes, biome selection, claim plugin integration, and command extensions.

---

## **Building from Source**

**Prerequisites:**
* **Java 21**
* **Maven 3.8+**

**Build command:**
```bash
mvn clean package
```

The compiled plugin will be available at:
```
target/RTP-<version>.jar
```

The output is fully shaded and ready for server deployment.

---

## **Development**

**Development Environment:**
* **Maven** for dependency and build management
* **GitHub Actions** for automated compilation and releases

Contributions are welcome! Feel free to fork, improve, or submit pull requests following standard coding best practices.

---

## **Addons & Extensions**

The plugin features a flexible extension system supporting:

* Custom shape implementations
* Biome selection logic
* Land-claim plugin integration
* Command enhancements

Reference implementations and examples are available in the `/addons` directory.

---

## **License**

This fork respects the original licensing terms and permissions of the upstream RTP project.