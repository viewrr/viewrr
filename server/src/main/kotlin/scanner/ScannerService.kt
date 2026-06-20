package wtf.jobin.scanner

import java.util.UUID

class ScannerService(private val scanner: MediaScanner) {
    suspend fun scanLibrary(libraryId: UUID): ScanResult = scanner.scan(libraryId)
}
