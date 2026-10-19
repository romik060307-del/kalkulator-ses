# RK Calculator Vault (Android)

Aplikasi Android ringan dengan tampilan kalkulator biasa dan ruang file pribadi lokal. Nama aplikasi di launcher: **Kalkulator**. Aplikasi tidak memerlukan akun, server, atau izin internet.

## Fitur

- Kalkulator standar: tambah, kurang, kali, bagi, persen, desimal, tanda negatif, dan tanda kurung melalui ekspresi.
- Ruang pribadi untuk foto, video, dan berkas umum.
- Impor dari pemilih file Android dan ekspor kembali ke lokasi yang dipilih.
- File dan metadata dienkripsi dengan AES-GCM; kunci utama dibungkus dengan PIN melalui PBKDF2. Pratinjau video memakai file sementara di cache privat aplikasi dan akan dibersihkan saat ditutup atau aplikasi dibuka kembali.
- File yang dihapus masuk ke Sampah lebih dahulu; bisa dipulihkan atau dihapus permanen.
- Pratinjau foto dan video di dalam aplikasi.
- Ganti PIN, pencarian file, filter foto/video/file, dan perlindungan screenshot saat ruang pribadi terbuka.
- Opsi menyembunyikan ikon launcher, dengan ubin Setelan Cepat dan tautan pemulihan.

## Cara masuk tanpa tulisan sandi di layar kalkulator

Perilaku masuk sengaja tidak menampilkan tulisan "masukkan sandi".

1. Saat baru dipasang, pilih kode angka 4–12 digit; disarankan 6–10 digit dan hindari kode yang mudah ditebak.
2. Ketik angka itu pada kalkulator lalu tekan `=` **dua kali berturut-turut tanpa mengubah ekspresi**. Ini akan menyimpan kode dan membuka ruang pribadi.
3. Pada pemakaian berikutnya, ketik kode yang sama dan tekan `=` dua kali berturut-turut.
4. Kode juga bisa berupa hasil operasi matematika yang menghasilkan angka bulat 4–12 digit. Yang dipakai sebagai kode adalah hasilnya, bukan tanda operasinya.
5. Tekan **Kalkulator** atau tombol Kembali untuk keluar dari ruang pribadi. Saat aplikasi masuk ke latar belakang, ruang pribadi dikunci kembali.

Penting: karena antarmukanya sengaja tetap seperti kalkulator, aplikasi tidak menampilkan panduan PIN pada layar awal. Simpan kode di tempat aman; tidak ada mekanisme reset PIN yang dapat membuka data terenkripsi tanpa kode lama.

## Penyimpanan dan batasan

- File disimpan pada direktori privat internal aplikasi, bukan di file APK itu sendiri. File tidak ditulis sebagai foto/video biasa ke Galeri. File hanya keluar dari ruang pribadi jika pengguna memilih **Ekspor ke HP**.
- Android menghapus data privat aplikasi jika data aplikasi dibersihkan atau aplikasi di-uninstall. Ekspor/cadangkan file penting sebelum menghapus aplikasi. Jika PIN terlupa, file terenkripsi tidak bisa dipulihkan.
- Fitur **Sembunyikan Ikon** menghilangkan ikon dari app drawer/launcher yang mendukung perubahan komponen. Android masih dapat menampilkan aplikasi dalam Pengaturan > Aplikasi; aplikasi tidak dapat menjamin menghilang dari daftar sistem atau semua launcher pihak ketiga.
- Sebelum ikon disembunyikan, tambahkan ubin Setelan Cepat bernama **Kalkulator** lewat edit ubin di panel notifikasi. Menekan ubin itu membuka kalkulator dan menampilkan lagi ikon. Jalur cadangan: buka tautan `rkcalc://restore` di perangkat Android (dukungan browser dapat berbeda).
- Ini proyek Android APK, bukan aplikasi iPhone/iOS.
- Versi saat ini dibuat sebagai fondasi awal. Perangkat dan provider pemilih berkas yang berbeda dapat berperilaku berbeda; uji dengan salinan file non-penting terlebih dahulu.

## Membangun APK lewat GitHub Actions

Workflow ada di file terpisah: `.github/workflows/build-apk.yml`.

1. Buat repository GitHub baru.
2. Unggah **isi folder proyek ini** ke root repository. Pastikan `.github/workflows/build-apk.yml` tetap berada pada jalur persis itu.
3. Buka tab **Actions** lalu pilih **Build RK Calculator APK**. Workflow juga berjalan otomatis saat push ke `main` atau `master`.
4. Setelah berstatus berhasil, buka run terbaru dan unduh artifact **RK-Calculator-APK**. Di dalamnya ada `app-debug.apk` untuk uji coba pada perangkat Android.

Untuk build lokal, buka folder ini di Android Studio versi yang mendukung Android Gradle Plugin 8.7.3, JDK 17, dan Android SDK 35.

## Struktur penting

```text
app/
  src/main/AndroidManifest.xml
  src/main/java/com/rk/calculatorvault/MainActivity.kt
  src/main/java/com/rk/calculatorvault/RecoveryActivity.kt
  src/main/java/com/rk/calculatorvault/RecoveryTileService.kt
.github/
  workflows/
    build-apk.yml
build.gradle.kts
settings.gradle.kts
```
