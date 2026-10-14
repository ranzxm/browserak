# Browserak

Browser Android ringan: UI native Kotlin + WebView sistem. Terinspirasi kesederhanaan Via, grabber 1DM, proteksi Brave, userscript Tampermonkey; **bukan salinan atau kompatibilitas penuh**.

## Build tanpa tool Android lokal

Push ke `main` menjalankan [GitHub Actions](https://github.com/ranzxm/browserak/actions): JDK 17, Android SDK 35, Gradle 8.11.1 seluruhnya di runner. Tidak perlu memasang Android Studio/SDK/JDK/Gradle di komputer pengembang. Buka run berhasil, unduh artifact `browserak-debug-apk`, ekstrak APK lalu instal di Android 8.0+.

Artifact `browserak-release-unsigned` memakai R8/resource shrinking, **belum dapat diinstal tanpa signing**. Keystore release belum dikonfigurasi; jangan commit keystore/password. Debug build untuk pengujian, bukan distribusi produksi. Kunci debug runner bersifat sementara; build berikutnya mungkin memerlukan uninstall (data hilang). Gunakan signing stabil sebelum distribusi.

## Anggaran ringan

- Tanpa Compose, AndroidX, browser engine bundel, analytics, akun, backend, atau library runtime pihak ketiga.
- Hanya **satu WebView aktif**. Maksimal 12 tab; tab tidak aktif menyimpan state/history, bukan DOM hidup. Berpindah tab dapat memuat ulang halaman dan kehilangan state formulir/JS.
- Maksimal 100 URL grabber/tab; bookmark/riwayat dibatasi.
- Unduhan berjalan sebagai foreground service; tidak ada layanan browser saat idle.
- Filter diperbarui manual, bukan polling. Userscript berjalan hanya pada URL yang cocok, nonaktif dalam sesi privat.
- Actions mencetak ukuran APK; build gagal jika release APK melewati **5 MiB**. Ini batas build, bukan ukuran hasil yang sudah diukur. RAM/baterai perlu pengukuran pada perangkat asli; belum ada klaim angka.

## Fitur

- UI native: toolbar ikon garis, address bar membulat, beranda pencarian/shortcut, panel bawah untuk menu/tab/grabber/unduhan. Tema gelap mencakup dialog sistem.
- Tab, pencarian DuckDuckGo, bookmark, riwayat, tema terang/gelap, navigasi.
- Grabber memantau URL request berakhiran media/file umum, memindai `video/audio/source` saat tombol Grab ditekan, menangkap download WebView; tersedia URL manual. Ukuran ditentukan downloader, bukan melakukan HEAD untuk setiap request.
- Downloader: dua file paralel, antrean persisten, notifikasi, pause/resume HTTP Range dengan validator, retry jaringan/HTTP sementara maksimal tiga kali. **Satu koneksi per file**, belum multi-range seperti 1DM. Tanpa validator ETag/Last-Modified, resume memulai ulang agar tidak mencampur konten.
- HLS VOD non-DRM: playlist lengkap, segmen MPEG-TS digabung menjadi transport stream (`.ts`), resume per segmen. Master playlist memakai **varian pertama**, belum pilihan kualitas. Live, enkripsi, fMP4, byte-range HLS, track audio/video terpisah ditolak; belum remux MP4.
- Adblock awal + pembaruan EasyList/EasyPrivacy; subset aturan jaringan/kosmetik, pengecualian per situs, cookie pihak ketiga diblokir. **Tidak setara Brave Shields**; YouTube, anti-adblock, service worker, fingerprinting tidak dijamin.
- Userscript lokal/editor/impor `.user.js` maksimal 256 KiB, persetujuan situs sasaran, aktif/nonaktif/hapus. `@match`, `@exclude`, fase `@run-at`, `GM_addStyle`, `GM_getValue`, `GM_setValue`, `GM_deleteValue` dengan keterbatasan di bawah.

## Batas penting

- Tidak melewati DRM/paywall; unduh hanya konten yang Anda berhak unduh. Blob URL, DASH, media terenkripsi, semua situs/video tidak dijamin terdeteksi.
- **`document-start` dijalankan terlambat pada akhir pemuatan**: WebView native tidak menyediakan hook aman yang dipakai proyek ini. Jangan gunakan script yang membutuhkan urutan awal persis. Tidak ada ekstensi Chrome, `GM_xmlhttpRequest`, `@require`, katalog atau update script otomatis.
- Script bukan sandbox dari situs: berjalan pada konteks halaman, dapat membaca/mengubah data situs. Storage GM memakai localStorage bernamespace per script **dan per origin**, dapat diperiksa situs, bukan tempat menyimpan rahasia. Tidak ada JavaScript bridge ke API Android.
- Mode privat adalah **sesi global**, bukan tab incognito terisolasi. Masuk/keluar menutup tab, menghapus cookie/login, cache, WebStorage; script/unduhan baru dinonaktifkan, riwayat tidak ditulis. Unduhan sebelumnya tetap berjalan/tersimpan. Mode privat tidak menyembunyikan trafik dari situs/ISP, tidak menjamin penghapusan forensik. Penanda sesi memicu pembersihan ulang setelah proses mati.
- HTTP sengaja tersedia untuk browsing, tetapi mixed content HTTP di halaman HTTPS diblokir. HTTPS direkomendasikan. Error sertifikat tidak dilewati. `file://`, embedded credentials, akses file WebView ditolak.
- Download tersimpan di direktori eksternal milik aplikasi, terhapus saat uninstall. Gunakan **Unduhan → Simpan salinan…** untuk menyimpan via pemilih dokumen Android.
- URL unduhan/cookie sesi yang diperlukan untuk resume tersimpan dalam data privat aplikasi; backup dinonaktifkan. Cookie tidak diteruskan ke origin lain saat redirect. URL bertoken tetap bersifat sensitif. Pause/resume juga bergantung masa berlaku URL/login. Layanan tidak dijadwalkan boot otomatis; unduhan yang terinterupsi dapat dilanjutkan dari menu. Android 15+ dapat membatasi durasi foreground service.

## Pemeriksaan

Workflow menjalankan `gradle --no-daemon :app:check :app:assembleDebug :app:assembleRelease`, mencakup unit test dan Android Lint. Tidak ada toolchain Android yang dijalankan lokal. Uji perangkat belum otomatis: cek login, navigasi/tab, script, adblock, pause/resume, jaringan putus, penyimpanan penuh, proses mati, sesi privat, ekspor file pada Android 8 dan Android terbaru.

Opsional: **Run workflow → visual_check** menjalankan emulator hanya di runner GitHub; artifact `ui-screenshots` berisi tangkapan beranda/menu dan log crash. Ini smoke check, bukan pengganti uji perangkat/accessibility.

Daftar filter diunduh dari sumber resmi; lihat pemberitahuan lisensi upstream yang disimpan bersama konten filter. Tidak ada daftar upstream yang dibundel atau diklaim sebagai karya proyek ini.
