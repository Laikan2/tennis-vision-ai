import Foundation
import Security

enum PairingStore {
    private static let query: [String:Any] = [kSecClass as String:kSecClassGenericPassword,
        kSecAttrService as String:"ai.tennisvision.camera.pairing",kSecAttrAccount as String:"windows"]
    static func read() -> Data? {
        var q=query; q[kSecReturnData as String]=true; q[kSecMatchLimit as String]=kSecMatchLimitOne
        var value: CFTypeRef?
        return SecItemCopyMatching(q as CFDictionary,&value) == errSecSuccess ? value as? Data : nil
    }
    static func save(_ data: Data) throws {
        let attributes: [String:Any] = [kSecValueData as String:data,
            kSecAttrAccessible as String:kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let result=SecItemUpdate(query as CFDictionary,attributes as CFDictionary)
        if result == errSecItemNotFound {
            guard SecItemAdd(query.merging(attributes){_,new in new} as CFDictionary,nil) == errSecSuccess else {
                throw CameraError.invalid("Não foi possível guardar o pareamento seguro neste iPhone.")
            }
        } else if result != errSecSuccess { throw CameraError.invalid("Não foi possível atualizar o pareamento salvo.") }
    }
}
