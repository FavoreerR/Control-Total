import SwiftUI
import FirebaseAuth
import FirebaseFirestore

struct Movement: Identifiable {
    let id: String
    let title: String
    let amount: Int
    let type: String
    let owner: String
    let month: String
}
@MainActor final class FinanceModel: ObservableObject {
    @Published var email = ""
    @Published var password = ""
    @Published var householdCode = ""
    @Published var householdId: String?
    @Published var movements: [Movement] = []
    @Published var error = ""
    @Published var title = ""
    @Published var amount = ""
    @Published var type = "Gasto"
    @Published var owner = "Familiar"
    @Published var signedIn = false
    private let db = Firestore.firestore()
    private var profileListener: ListenerRegistration?
    private var movementsListener: ListenerRegistration?
    private var authHandle: AuthStateDidChangeListenerHandle?
    init() {
        authHandle = Auth.auth().addStateDidChangeListener { [weak self] _, user in
            Task { @MainActor in
                guard let self else { return }
                self.profileListener?.remove()
                self.movementsListener?.remove()
                self.householdId = nil
                self.movements = []
                self.signedIn = user != nil
                self.email = user?.email ?? ""
                if let uid = user?.uid { self.observeProfile(uid) }
            }
        }
    }
    private func observeProfile(_ uid: String) {
        profileListener = db.collection("profiles").document(uid).addSnapshotListener { [weak self] snapshot, err in
            Task { @MainActor in
                guard let self else { return }
                if let err { self.error = err.localizedDescription; return }
                let id = snapshot?.data()?["householdId"] as? String
                guard self.householdId != id else { return }
                self.householdId = id
                self.movementsListener?.remove()
                self.movements = []
                if let id { self.observeMovements(id) }
            }
        }
    }
    private func observeMovements(_ id: String) {
        movementsListener = db.collection("households").document(id).collection("entries").addSnapshotListener { [weak self] snapshot, err in
            Task { @MainActor in
                guard let self else { return }
                if let err { self.error = err.localizedDescription; return }
                self.movements = snapshot?.documents.compactMap { doc in
                    let d = doc.data()
                    guard let title = d["title"] as? String, let amount = d["amount"] as? Int,
                          let type = d["type"] as? String, let month = d["month"] as? String else { return nil }
                    return Movement(id: doc.documentID, title: title, amount: amount, type: type,
                                    owner: d["owner"] as? String ?? "Familiar", month: month)
                } ?? []
            }
        }
    }
    func login(create: Bool) {
        Task {
            do {
                if create { _ = try await Auth.auth().createUser(withEmail: email, password: password) }
                else { _ = try await Auth.auth().signIn(withEmail: email, password: password) }
                error = ""
            } catch { self.error = error.localizedDescription }
        }
    }
    func createHousehold() {
        guard let uid = Auth.auth().currentUser?.uid else { return }
        let id = UUID().uuidString.lowercased()
        Task {
            do {
                try await db.collection("households").document(id).setData(["ownerUid": uid, "members": [uid: true]])
                try await db.collection("profiles").document(uid).setData(["householdId": id])
            } catch { self.error = error.localizedDescription }
        }
    }
    func joinHousehold() {
        guard let uid = Auth.auth().currentUser?.uid else { return }
        let id = householdCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return }
        Task {
            do {
                let ref = db.collection("households").document(id)
                let snapshot = try await ref.getDocument()
                guard snapshot.exists else { self.error = "Hogar no encontrado"; return }
                try await ref.updateData(["members.\(uid)": true])
                try await db.collection("profiles").document(uid).setData(["householdId": id])
            } catch { self.error = error.localizedDescription }
        }
    }
    func add() {
        guard let id = householdId, let uid = Auth.auth().currentUser?.uid,
              let value = Int(amount), value > 0, !title.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        let month = String(ISO8601DateFormatter().string(from: Date()).prefix(7))
        Task {
            do {
                _ = try await db.collection("households").document(id).collection("entries").addDocument(data: [
                    "title": title, "amount": value, "type": type, "owner": owner,
                    "month": month, "createdBy": uid, "createdAt": Int(Date().timeIntervalSince1970 * 1000)
                ])
                title = ""; amount = ""
            } catch { self.error = error.localizedDescription }
        }
    }
    func logout() { try? Auth.auth().signOut() }
}
struct ContentView: View {
    @StateObject private var model = FinanceModel()
    private let background = Color(red: 0.047, green: 0.063, blue: 0.145)
    private let card = Color(red: 0.153, green: 0.133, blue: 0.306)
    private var month: String { String(ISO8601DateFormatter().string(from: Date()).prefix(7)) }
    private func money(_ amount: Int) -> String {
        amount.formatted(.currency(code: "CLP").precision(.fractionLength(0)))
    }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("CONTROL TOTAL").font(.largeTitle.bold()).foregroundStyle(.purple)
                    Text("Finanzas familiares").foregroundStyle(.white.opacity(0.7))
                    if !model.error.isEmpty { Text(model.error).foregroundStyle(.red) }
                    if !model.signedIn {
                        VStack {
                            TextField("Correo", text: $model.email).textContentType(.emailAddress).keyboardType(.emailAddress).textInputAutocapitalization(.never)
                            SecureField("Contraseña", text: $model.password)
                            Button("Iniciar sesión") { model.login(create: false) }
                            Button("Crear cuenta") { model.login(create: true) }
                        }.padding().background(card, in: RoundedRectangle(cornerRadius: 18))
                    } else if model.householdId == nil {
                        VStack {
                            Button("Crear hogar familiar") { model.createHousehold() }
                            TextField("Código del hogar", text: $model.householdCode).textInputAutocapitalization(.never)
                            Button("Unirme al hogar") { model.joinHousehold() }
                        }.padding().background(card, in: RoundedRectangle(cornerRadius: 18))
                        Button("Cerrar sesión") { model.logout() }
                    } else {
                        Text("Código del hogar: \(model.householdId ?? "")").font(.caption).textSelection(.enabled)
                        let current = model.movements.filter { $0.month == month }
                        let income = current.filter { $0.type == "Ingreso" }.reduce(0) { $0 + $1.amount }
                        let expenses = current.filter { $0.type == "Gasto" }.reduce(0) { $0 + $1.amount }
                        let debts = current.filter { $0.type == "Deuda" }.reduce(0) { $0 + $1.amount }
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Ingresos: \(money(income))")
                            Text("Gastos: \(money(expenses))")
                            Text("Deudas: \(money(debts))")
                            Text("Disponible: \(money(income - expenses))").font(.title2.bold())
                        }.frame(maxWidth: .infinity, alignment: .leading).padding().background(card, in: RoundedRectangle(cornerRadius: 18))
                        VStack {
                            TextField("Descripción", text: $model.title)
                            TextField("Monto en pesos", text: $model.amount).keyboardType(.numberPad)
                            Picker("Tipo", selection: $model.type) {
                                ForEach(["Ingreso", "Gasto", "Deuda"], id: \.self) { Text($0) }
                            }
                            Picker("Responsable", selection: $model.owner) {
                                ForEach(["Familiar", "Yo", "Esposa"], id: \.self) { Text($0) }
                            }
                            Button("Guardar movimiento") { model.add() }
                        }.padding().background(card, in: RoundedRectangle(cornerRadius: 18))
                        Text("Historial del mes").font(.title2.bold())
                        ForEach(current) { item in
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(item.title).bold()
                                    Text("\(item.type) · \(item.owner)").font(.caption)
                                }
                                Spacer()
                                Text(money(item.amount))
                            }.padding().background(card, in: RoundedRectangle(cornerRadius: 12))
                        }
                        Button("Cerrar sesión") { model.logout() }
                    }
                }.padding()
            }.background(background).foregroundStyle(.white)
        }.tint(.purple)
    }
}
