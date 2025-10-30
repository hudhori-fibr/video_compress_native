## 1.1.25

* Commit ulang seluruh perubahan kode compressVideo (iOS & Android) agar konsisten dengan versi pub.dev

## 1.1.24

* Perbaikan kode & publish ulang setelah update kualitas compressVideo (iOS & Android)

## 1.1.23

* [iOS] `compressVideo` kini menggunakan preset kualitas tertinggi (tidak burem)
* [Android] `compressVideo` otomatis mengikuti bitrate video source (hasil lebih jernih)
* Perbaikan kualitas hasil re-encode di kedua platform

## 1.1.22

* Add `compressVideo()` method for video re-encoding without trim/resize
* Support standard codec conversion for video compatibility  
* Add progress monitoring for compression operations
* Cross-platform support (iOS & Android)
* Updated documentation and examples

## 0.1.0

* Add `compressVideo()` method for video re-encoding without trim/resize
* Support standard codec conversion for video compatibility
* Add progress monitoring for compression operations
* Cross-platform support (iOS & Android)

## 0.0.1

* Initial release with video processing capabilities
* Support for `compressAndTrim()` method with resize and trimming
* Support for `trimVideo()` method for video trimming only
