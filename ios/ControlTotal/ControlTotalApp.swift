import SwiftUI
import FirebaseCore
import FirebaseAuth
import FirebaseFirestore

@main struct ControlTotalApp: App {
    init() { FirebaseApp.configure() }
    @StateObject private var model = FamilyModel()
    var body: some Scene {
        WindowGroup { RootView().environmentObject(model).preferredColorScheme(.dark) }
    }
}

struct Movement: Identifiable {
    let id: String
    let title: String
    let amount: Int
    let type: String
    let owner: String
    let month: String
    let createdAt: Double
    init?(id: String, data: [String: Any]) {
        guard let title = data["title"] as? String,
              let type = data["type"] as? String,
              let owner = data["owner"] as? String,
              let month = data["month"] as? String else { return nil }
        self.id = id
        self.title = title
        self.type = type
        self.owner = owner
        self.month = month
        self.amount = (data["amount"] as? NSNumber)?.intValue ?? 0
        self.createdAt = (data["createdAt"] as? NSNumber)?.doubleValue ?? 0
    }
}

@MainActor final class FamilyModel: ObservableObject {
    @Published var user: User?
    @Published var householdId: String?
    @Published var entries: [Movement] = []
    @Published var error = ""
    @Published var loading = true
    private let db = Firestore.firestore()
    private var authHandle: AuthStateDidChangeListenerHandle?
    private var profileListener: ListenerRegistration?
    private var entryListener: ListenerRegistration?

    init() {
        authHandle = Auth.auth().addStateDidChangeListener { [weak self] _, user in
            Task { @MainActor in self?.handleUser(user) }
        }
    }

    private func handleUser(_ user: User?) {
        profileListener?.remove()
        entryListener?.remove()
        profileListener = nil
        entryListener = nil
        self.user = user
        householdId = nil
        entries = []
        loading = user != nil
        guard let uid = user?.uid else { loading = false; return }
        profileListener = db.collection("profiles").document(uid).addSnapshotListener { [weak self] snap, err in
            Task { @MainActor in
                guard let self else { return }
                if let err { self.error = err.localizedDescription; self.loading = false; return }
                self.updateHousehold(snap?.data()?["householdId"] as? String)
            }
        }
    }

    private func updateHousehold(_ id: String?) {
        guard id != householdId else { loading = false; return }
        entryListener?.remove()
        entryListener = nil
        householdId = id
        entries = []
        loading = false
        guard let id, !id.isEmpty else { return }
        entryListener = db.collection("households").document(id).collection("entries")
            .addSnapshotListener { [weak self] snap, err in
                Task { @MainActor in
                    guard let self else { return }
                    if let err { self.error = err.localizedDescription; return }
                    self.entries = snap?.documents.compactMap { Movement(id: $0.documentID, data: $0.data()) } ?? []
                }
            }
    }

    func login(email: String, password: String, register: Bool) async {
        do {
            if register {
                _ = try await Auth.auth().createUser(withEmail: email, password: password)
            } else {
                _ = try await Auth.auth().signIn(withEmail: email, password: password)
            }
            error = ""
        } catch { self.error = error.localizedDescription }
    }

    func logout() { do { try Auth.auth().signOut() } catch { self.error = error.localizedDescription } }

    func createHousehold() async {
        guard let uid = user?.uid else { return }
        let id = UUID().uuidString.lowercased()
        do {
            try await db.collection("households").document(id).setData(["ownerUid": uid, "members": [uid: true]])
            try await db.collection("profiles").document(uid).setData(["householdId": id])
        } catch { self.error = error.localizedDescription }
    }

    func joinHousehold(code: String) async {
        guard let uid = user?.uid else { return }
        let id = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return }
        do {
            let ref = db.collection("households").document(id)
            let snap = try await ref.getDocument()
            guard snap.exists else { self.error = "No existe ese hogar"; return }
            try await ref.updateData(["members.\(uid)": true])
            try await db.collection("profiles").document(uid).setData(["householdId": id])
            error = ""
        } catch { self.error = error.localizedDescription }
    }

    func add(title: String, amount: Int, type: String, owner: String, month: String) async {
        guard let id = householdId, let uid = user?.uid, !title.isEmpty, amount > 0 else { return }
        do {
            _ = try await db.collection("households").document(id).collection("entries").addDocument(data: [
                "title": title, "amount": amount, "type": type, "owner": owner,
                "month": month, "createdBy": uid, "createdAt": Date().timeIntervalSince1970 * 1000
            ])
            error = ""
        } catch { self.error = error.localizedDescription }
    }

    func delete(_ entry: Movement) async {
        guard let id = householdId else { return }
        do { try await db.collection("households").document(id).collection("entries").document(entry.id).delete() }
        catch { self.error = error.localizedDescription }
    }
}

private let purple = Color(red: 0.53, green: 0.39, blue: 0.92)
private let panel = Color(red: 0.14, green: 0.13, blue: 0.28)
private let background = Color(red: 0.05, green: 0.07, blue: 0.15)

struct RootView: View {
    @EnvironmentObject var model: FamilyModel
    var body: some View {
        NavigationStack {
            Group {
                if model.loading { ProgressView("Sincronizando...") }
                else if model.user == nil { LoginView() }
                else if model.householdId == nil { HouseholdView() }
                else { DashboardView() }
            }
            .navigationTitle("CONTROL TOTAL")
            .toolbarColorScheme(.dark)
            .background(background.ignoresSafeArea())
            .tint(purple)
            .safeAreaInset(edge: .bottom) {
                if !model.error.isEmpty {
                    Text(model.error).font(.footnote).foregroundStyle(.red)
                        .padding(10).frame(maxWidth: .infinity).background(panel)
                }
            }
        }
    }
}

struct LoginView: View {
    @EnvironmentObject var model: FamilyModel
    @State private var email = ""
    @State private var password = ""
    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "chart.pie.fill").font(.system(size: 65)).foregroundStyle(purple)
            Text("Finanzas familiares").font(.title2.bold())
            TextField("Correo electrónico", text: $email)
                .textContentType(.emailAddress).keyboardType(.emailAddress).textInputAutocapitalization(.never)
                .autocorrectionDisabled().textFieldStyle(.roundedBorder)
            SecureField("Contraseña", text: $password).textFieldStyle(.roundedBorder)
            Button("Iniciar sesión") { Task { await model.login(email: email, password: password, register: false) } }
                .buttonStyle(.borderedProminent)
            Button("Crear cuenta") { Task { await model.login(email: email, password: password, register: true) } }
                .buttonStyle(.bordered)
            Spacer()
        }.padding(24)
    }
}

struct HouseholdView: View {
    @EnvironmentObject var model: FamilyModel
    @State private var code = ""
    var body: some View {
        VStack(spacing: 20) {
            Text("Tu hogar familiar").font(.title2.bold())
            Text("Si tu pareja ya tiene un hogar, utiliza su código para compartir todos los movimientos.")
                .foregroundStyle(.secondary)
            TextField("Código del hogar", text: $code).textInputAutocapitalization(.never)
                .autocorrectionDisabled().textFieldStyle(.roundedBorder)
            Button("Unirme al hogar") { Task { await model.joinHousehold(code: code) } }
                .buttonStyle(.borderedProminent)
            Button("Crear hogar nuevo") { Task { await model.createHousehold() } }
                .buttonStyle(.bordered)
            Button("Cerrar sesión") { model.logout() }
            Spacer()
        }.padding(24)
    }
}

struct DashboardView: View {
    @EnvironmentObject var model: FamilyModel
    @State private var month = Date()
    @State private var title = ""
    @State private var amount = ""
    @State private var type = "Gasto"
    @State private var owner = "Familiar"
    private var monthKey: String {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM"; return f.string(from: month)
    }
    private var visible: [Movement] {
        model.entries.filter { $0.month == monthKey }.sorted { $0.createdAt > $1.createdAt }
    }
    private func total(_ type: String) -> Int { visible.filter { $0.type == type }.reduce(0) { $0 + $1.amount } }
    private func pesos(_ n: Int) -> String { n.formatted(.currency(code: "CLP").precision(.fractionLength(0))) }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(model.user?.email ?? "").font(.footnote).foregroundStyle(.secondary)
                    Text("Código del hogar").font(.caption)
                    Text(model.householdId ?? "").font(.caption.monospaced()).textSelection(.enabled)
                }.card()
                HStack {
                    Button { month = Calendar.current.date(byAdding: .month, value: -1, to: month) ?? month } label: { Image(systemName: "chevron.left") }
                    Spacer()
                    Text(month.formatted(.dateTime.month(.wide).year())).font(.headline)
                    Spacer()
                    Button { month = Calendar.current.date(byAdding: .month, value: 1, to: month) ?? month } label: { Image(systemName: "chevron.right") }
                        .disabled(monthKey >= Date().formatted(.dateTime.year().month(.twoDigits)))
                }.card()
                VStack(alignment: .leading, spacing: 10) {
                    Text("Ingresos familiares").foregroundStyle(.secondary)
                    Text(pesos(total("Ingreso"))).font(.largeTitle.bold()).foregroundStyle(.green)
                    Label("Gastos: \(pesos(total("Gasto")))", systemImage: "arrow.up.right")
                    Label("Deudas: \(pesos(total("Deuda")))", systemImage: "creditcard")
                    Divider()
                    Text("Disponible: \(pesos(total("Ingreso") - total("Gasto")))").font(.title3.bold())
                }.card()
                VStack(alignment: .leading, spacing: 12) {
                    Text("Nuevo movimiento").font(.headline)
                    TextField("Descripción", text: $title).textFieldStyle(.roundedBorder)
                    TextField("Monto en pesos", text: $amount).keyboardType(.numberPad).textFieldStyle(.roundedBorder)
                    Picker("Tipo", selection: $type) {
                        ForEach(["Ingreso", "Gasto", "Deuda"], id: \.self) { Text($0) }
                    }
                    Picker("Responsable", selection: $owner) {
                        ForEach(["Familiar", "Yo", "Esposa"], id: \.self) { Text($0) }
                    }
                    Button("Guardar movimiento") {
                        guard let n = Int(amount), n > 0, !title.trimmingCharacters(in: .whitespaces).isEmpty else { return }
                        Task { await model.add(title: title.trimmingCharacters(in: .whitespaces), amount: n, type: type, owner: owner, month: monthKey); title = ""; amount = "" }
                    }.buttonStyle(.borderedProminent)
                }.card()
                VStack(alignment: .leading, spacing: 12) {
                    Text("Historial del mes").font(.headline)
                    if visible.isEmpty { Text("Sin movimientos este mes").foregroundStyle(.secondary) }
                    ForEach(visible) { entry in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(entry.title).font(.headline)
                                Text("\(entry.type) · \(entry.owner)").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(pesos(entry.amount)).font(.subheadline.bold())
                            Button(role: .destructive) { Task { await model.delete(entry) } } label: { Image(systemName: "trash") }
                        }
                        Divider()
                    }
                }.card()
                Button("Cerrar sesión") { model.logout() }.padding()
            }.padding(16)
        }
    }
}

private struct CardModifier: ViewModifier {
    func body(content: Content) -> some View {
        content.padding(18).frame(maxWidth: .infinity, alignment: .leading)
            .background(panel, in: RoundedRectangle(cornerRadius: 18))
    }
}
private extension View { func card() -> some View { modifier(CardModifier()) } }
