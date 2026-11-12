# Türkçe TV (Android TV / Google TV)

Türkçe TV, televizyonda çalışan bir altyazı çevirmenidir. Kendi oyun konsoluna ev ağından uzaktan oynatma bağlantısıyla bağlanır, oyun görüntüsündeki İngilizce altyazıyı okur, cihazın içinde Türkçeye çevirir ve oyunun üstünde gösterir. Okuma ve çeviri televizyonda yapılır; geliştiricinin sunucusu yoktur.

Bu depo, Google Play'de dağıtılan uygulamanın kaynak kodudur. Her mağaza sürümü burada aynı adla etiketlenir (ör. `v1.0.0`).

- Gizlilik politikası: https://cihanszgn.github.io/turkce-tv-android/gizlilik.html
- Sorun bildirimi: bu deponun "Issues" bölümü

Bu uygulama Sony Interactive Entertainment ile bağlantılı, onaylı veya destekli değildir. PlayStation ve PS5, sahiplerinin tescilli markalarıdır.

## Lisans

Uygulama GNU Affero General Public License v3.0 ile lisanslıdır (`LICENSE`). PS5 bağlantısı için [chiaki-ng](https://github.com/streetpea/chiaki-ng) (AGPL-3.0, OpenSSL istisnasıyla) kullanılır. Pakete giren bütün bileşenler ve lisansları `UCUNCU-TARAF.txt` dosyasında ve uygulamanın "Hakkında ve lisanslar" ekranında listelenir.

## Derleme

Gerekenler: JDK 17, Android SDK (platform 36, build-tools 35+), Android NDK r28, CMake ve Ninja. Betikler Windows PowerShell içindir.

1. Yerel kütüphanelerin kaynaklarını indir:
   - chiaki-ng `v1.10.0` → `%LOCALAPPDATA%\TurkceTV\chiaki\chiaki-ng`
   - Mbed TLS `v2.28.10` → `%LOCALAPPDATA%\TurkceTV\chiaki\mbedtls`
   - bergamot-translator (https://github.com/DavidVentura/bergamot-translator, commit `8e68407`, alt modülleriyle) → `%LOCALAPPDATA%\TurkceTV\motor\bergamot-sys\bergamot-translator`
   - NDK r28 ve CMake → `%LOCALAPPDATA%\TurkceTV\motor\araclar\` (`android-ndk-r28`, `cmake`)
2. Yerel kütüphaneleri derle (çıktı `app\src\main\jniLibs\<abi>\`):
   ```
   .\chiaki\derle.ps1
   .\motor\derle.ps1
   ```
   `motor\derle.ps1`, 32 bit ARM için gereken `marian-32bit.patch` yamasını kendisi uygular.
3. Uygulamayı derle:
   ```
   .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
   ```
   Yayın imzası depo dışındaki bir dosyadan okunur (`app\build.gradle.kts`); dosya yoksa yayın paketi imzasız çıkar.

Çeviri modeli (`app\src\main\assets\kucuk`) Mozilla'nın Firefox çeviri modellerinden İngilizce-Türkçe küçük modeldir (MPL-2.0). Deneme modundaki örnek görüntü `arac\demo_klip_uret.py` ile, ikon ve görseller `arac\gorsel_uret.py` ile üretilir.

---

# Türkçe TV (English)

Türkçe TV is a subtitle translator that runs on an Android TV or Google TV device. It connects to your own game console over the home network with a remote play link, reads the English subtitles in the game picture, translates them to Turkish on the device and shows them over the game. Reading and translation happen on the TV; the developer runs no server.

This repository is the source code of the app distributed on Google Play. Each store release is tagged here with the same name (for example `v1.0.0`).

- Privacy policy: https://cihanszgn.github.io/turkce-tv-android/gizlilik.html
- Bug reports: the "Issues" section of this repository

This app is not affiliated with, endorsed by or sponsored by Sony Interactive Entertainment. PlayStation and PS5 are trademarks of their owners.

Licensed under the GNU Affero General Public License v3.0 (`LICENSE`). Third-party components and their licences are listed in `UCUNCU-TARAF.txt`. Build steps are the ones in the Turkish section above: fetch chiaki-ng `v1.10.0`, Mbed TLS `v2.28.10` and bergamot-translator `8e68407`, run `chiaki\derle.ps1` and `motor\derle.ps1` with NDK r28, then `gradlew.bat :app:assembleDebug`.
