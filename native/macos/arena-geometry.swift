// Read-only projection of reviewed Unity UI transforms. No game calls or pixels.
private struct ArenaRect {
    let x: Double, y: Double, w: Double, h: Double
    var right: Double { x + w }
    var top: Double { y + h }
    func intersection(_ other: ArenaRect) -> ArenaRect? {
        let l = max(x, other.x), b = max(y, other.y), r = min(right, other.right), t = min(top, other.top)
        return r > l && t > b ? ArenaRect(x: l, y: b, w: r-l, h: t-b) : nil
    }
}

private final class ArenaGeometry {
    let r: DeckRuntime
    var m: Memory { r.memory }
    var p: Profile { r.profile }
    let layout: [String: Any]
    let screen: [Int]
    var root: UInt64 = 0
    var canvasRect: ArenaRect?
    var trsCache: [UInt64: [Double]] = [:]
    var rows: [[Any]] = []
    var occlusions: [[Double]] = []
    var packViews: [UInt64] = []

    init(_ runtime: DeckRuntime) throws {
        r = runtime
        guard let spec = runtime.profile.object["unity_geometry"] as? [String: Any] else { throw ReadFailure("changing", "Reading card positions.") }
        layout = spec
        let manager = try runtime.memory.pointer(runtime.unitySlide + number(spec, "screen_manager_vm"))
        let dims = try runtime.memory.read(manager + 0xb8, 8)
        let w = Int(try integer(dims, 0, UInt32.self)), h = Int(try integer(dims, 4, UInt32.self))
        try require((320...16384).contains(w) && (200...16384).contains(h), "Invalid Arena viewport.")
        screen = [w,h]
    }
    func marker(_ key: String) throws -> UInt64 { try r.unitySlide + number(layout, key) }
    func u32(_ address: UInt64) throws -> UInt32 { try integer(m.read(address, 4)) }
    func native(_ managed: UInt64) throws -> UInt64 { try m.pointer(managed + 16) }
    func floats(_ address: UInt64, _ count: Int) throws -> [Double] {
        let data = try m.read(address, count * 4)
        return try (0..<count).map { i in
            let value = Double(Float(bitPattern: try integer(data, i * 4, UInt32.self)))
            try require(value.isFinite && abs(value) < 1e7, "Invalid Arena transform.")
            return value
        }
    }
    func transform(_ component: UInt64) throws -> UInt64 {
        let owner = try m.pointer(component + 0x20), count = try u32(owner + 0x30)
        try require(count > 0 && count <= 64, "Invalid Arena component list.")
        let value = try m.pointer(m.pointer(owner + 0x20) + 8)
        try require(try m.pointer(value + 0x20) == owner, "Unexpected Arena Transform owner.")
        return value
    }
    func active(_ component: UInt64) throws -> Bool {
        try m.read(m.pointer(component + 0x20) + 0x47, 1)[0] == 1
    }
    func establishCanvas(_ transform: UInt64) throws {
        if root != 0 {
            // Hover previews and pack cards can live under separate screen-space canvases.
            // Re-establish that canvas rather than applying the previous canvas's transform.
            if try m.pointer(transform + 0x28) == m.pointer(root + 0x28) { return }
            root = 0; canvasRect = nil
        }
        var current = transform, visited = Set<UInt64>()
        for _ in 0..<48 {
            try require(visited.insert(current).inserted, "Invalid Arena parent chain.")
            let owner = try m.pointer(current + 0x20), count = Int(try u32(owner + 0x30))
            try require(count > 0 && count <= 64, "Invalid Arena component list.")
            let components = try m.read(m.pointer(owner + 0x20), count * 16)
            for i in 0..<count {
                let component: UInt64 = try integer(components, i * 16 + 8)
                if component == 0 { continue }
                if try m.pointer(component) != marker("canvas_vtable_vm") { continue }
                var canvas = component, parents = Set<UInt64>()
                for _ in 0..<32 {
                    try require(parents.insert(canvas).inserted && (try m.pointer(canvas)) == marker("canvas_vtable_vm"), "Invalid Arena Canvas chain.")
                    let parent = try m.pointer(canvas + 0x230, nullable: true)
                    if parent == 0 { break }
                    canvas = parent
                }
                let mode = try u32(canvas + 0x2c)
                try require(mode <= 1, "Arena is using an unsupported world-space Canvas.")
                root = try self.transform(canvas)
                canvasRect = try rect(root)
                try require(abs(canvasRect!.w / canvasRect!.h - Double(screen[0]) / Double(screen[1])) < 0.02, "Arena viewport is changing.")
                return
            }
            current = try m.pointer(current + 0x68, nullable: true)
            if current == 0 { break }
        }
        throw ReadFailure("changing", "Reading Arena's card canvas.")
    }
    func rect(_ transform: UInt64) throws -> ArenaRect {
        try require(try m.pointer(transform) == marker("rect_transform_vtable_vm"), "Unexpected Arena UI rectangle.")
        guard try m.read(transform + 0xf1, 1)[0] == 0 else { throw ReadFailure("changing", "Arena is updating the layout.") }
        let a = try floats(transform + 0xb8, 4)
        try require(a[2] > 0 && a[3] > 0, "Empty Arena rectangle.")
        return ArenaRect(x:a[0], y:a[1], w:a[2], h:a[3])
    }
    func relative(_ transform: UInt64, _ input: [Double]) throws -> [Double] {
        let hierarchy = try m.pointer(transform + 0x28), rootHierarchy = try m.pointer(root + 0x28)
        try require(hierarchy == rootHierarchy, "Arena changed transform hierarchies.")
        var index = Int(try u32(transform + 0x30)), point = input
        let rootIndex = Int(try u32(root + 0x30)), data = try m.pointer(hierarchy + 0x18), parents = try m.pointer(hierarchy + 0x20)
        var visited = Set<Int>()
        while index != rootIndex {
            try require(index >= 0 && index < 16384 && visited.count < 64 && visited.insert(index).inserted, "Invalid Arena transform hierarchy.")
            let address = data + UInt64(index * 48)
            let v: [Double]
            if let cached = trsCache[address] { v = cached }
            else { v = try floats(address, 12); trsCache[address] = v }
            let norm = v[4]*v[4] + v[5]*v[5] + v[6]*v[6] + v[7]*v[7]
            try require(abs(norm-1) < 0.005 && v[8...10].allSatisfy { $0 > 0 && $0 < 10000 }, "Invalid Arena rotation or scale.")
            let x=point[0]*v[8], y=point[1]*v[9], z=point[2]*v[10]
            let tx=2*(v[5]*z-v[6]*y), ty=2*(v[6]*x-v[4]*z), tz=2*(v[4]*y-v[5]*x)
            point=[v[0]+x+v[7]*tx+v[5]*tz-v[6]*ty,
                   v[1]+y+v[7]*ty+v[6]*tx-v[4]*tz,
                   v[2]+z+v[7]*tz+v[4]*ty-v[5]*tx]
            index = Int(try integer(m.read(parents + UInt64(index * 4), 4), 0, Int32.self))
        }
        return point
    }
    func projected(_ transform: UInt64, center: [Double], size: [Double]) throws -> ArenaRect {
        try establishCanvas(transform)
        let points = try [(-0.5,-0.5),(0.5,-0.5),(-0.5,0.5),(0.5,0.5)].map { a,b in
            try relative(transform,[center[0]+size[0]*a, center[1]+size[1]*b, center[2]])
        }
        let xs=points.map { $0[0] }, ys=points.map { $0[1] }
        let value = ArenaRect(x:xs.min()!, y:ys.min()!, w:xs.max()!-xs.min()!, h:ys.max()!-ys.min()!)
        try require(value.w > 0 && value.h > 0 && value.w < 20000 && value.h < 20000, "Invalid projected card bounds.")
        return value
    }
    func uiRect(_ transform: UInt64) throws -> ArenaRect {
        let q=try rect(transform)
        return try projected(transform,center:[q.x+q.w/2,q.y+q.h/2,0],size:[q.w,q.h,0])
    }
    func cardRect(_ view: UInt64) throws -> ArenaRect {
        try colliderRect(native(m.pointer(view + p.offset("cdc_meta","_cardCollider"))))
    }
    func colliderRect(_ collider: UInt64) throws -> ArenaRect {
        try require(try m.pointer(collider) == marker("box_collider_vtable_vm"), "Unexpected Arena card collider.")
        let center=try floats(collider+0x60,3), size=try floats(collider+0x6c,3)
        try require(size.allSatisfy { $0 > 0 && $0 < 10000 }, "Invalid card collider size.")
        return try projected(transform(collider),center:center,size:size)
    }
    func clip(_ scroll: UInt64) throws -> ArenaRect {
        try r.check(scroll,"scroll_rect")
        var view=try m.pointer(scroll + p.offset("scroll_rect","m_Viewport"),nullable:true)
        if view == 0 { view=try m.pointer(scroll+p.offset("scroll_rect","m_ViewRect"),nullable:true) }
        let t=try view == 0 ? transform(native(scroll)) : native(view)
        return try uiRect(t)
    }
    func append(_ id: UInt64, _ count: UInt64, _ zone: String, _ bounds: ArenaRect, clip: ArenaRect? = nil) throws {
        guard let canvas=canvasRect, let visible=bounds.intersection(clip ?? canvas)?.intersection(canvas),
              zone != "pack" || (abs(visible.top-bounds.top)<0.5 && visible.w >= bounds.w*0.98) else { return }
        let x=(visible.x-canvas.x)/canvas.w, y=(canvas.top-visible.top)/canvas.h, w=visible.w/canvas.w, h=visible.h/canvas.h
        guard x >= 0 && y >= 0 && w > 0 && h > 0 && x+w <= 1.000001 && y+h <= 1.000001 else { return }
        rows.append([id,count,zone,x,y,w,h])
    }
    func result() throws -> [String: Any] {
        try hoverPreview()
        return ["screen":screen,"cards":rows,"occlusions":occlusions]
    }
    func hoverPreview() throws {
        guard let service = try r.activeServices(["zoom_delegator"], required:false)["zoom_delegator"] else { return }
        for (field,key) in [("_cardRolloverZoom","rollover_zoom"),("_cardDetailsZoom","static_rollover_zoom")] {
            let zoom = try m.pointer(service + p.offset("zoom_delegator",field),nullable:true)
            if zoom == 0 { continue }
            try r.check(zoom,key)
            guard try r.flag(zoom,"rollover_base","<IsActive>k__BackingField"), try active(native(zoom)) else { continue }
            let card = try m.pointer(zoom + p.offset("rollover_base","_zoomCard"))
            try r.check(card,"meta_cdc")
            guard try active(native(card)) else { continue }
            let model = try m.pointer(card + p.offset("base_cdc","_model"))
            let id = try r.cardID(model)
            let bounds = try colliderRect(native(m.pointer(card + p.offset("base_cdc","<Collider>k__BackingField"))))
            try append(id,1,"preview",bounds)
            let hovered = try packViews.enumerated().filter { try r.flag($0.element,"meta_card_view","<IsMouseOver>k__BackingField") }
            if hovered.count == 1, rows.last?[2] as? String == "preview" {
                rows[rows.count-1].append(hovered[0].offset)
            }
            if key == "rollover_zoom" {
                let hanger = try m.pointer(zoom + p.offset(key,"_abilityHanger"),nullable:true)
                if hanger != 0, try r.flag(hanger,"hanger_base","<Active>k__BackingField") {
                    let view = try m.pointer(hanger + p.offset("ability_hanger","_view"))
                    try r.check(view,"ability_hanger_view")
                    if try active(native(view)), let canvas=canvasRect,
                       let visible = try uiRect(transform(native(view))).intersection(canvas) {
                        occlusions.append([(visible.x-canvas.x)/canvas.w,(canvas.top-visible.top)/canvas.h,visible.w/canvas.w,visible.h/canvas.h])
                    }
                }
            }
            guard try r.flag(zoom,"rollover_base","<IsActive>k__BackingField"),
                  try m.pointer(card + p.offset("base_cdc","_model")) == model,
                  try m.pointer(zoom + p.offset("rollover_base","_zoomCard")) == card else {
                throw ReadFailure("changing","Arena is changing its card preview.")
            }
        }
    }
    func pack(_ views: [UInt64]) throws -> [String: Any] {
        packViews = views
        for view in views {
            guard try active(native(view)) else { throw ReadFailure("changing","Arena is showing the new cards.") }
            let item=try r.field(view,"draft_card_view","<CurrentCard>k__BackingField","card_collection_item")
            let card=try r.field(item,"card_collection_item","<Card>k__BackingField","card_data")
            let q=try cardRect(view)
            guard try m.pointer(view+p.offset("draft_card_view","<CurrentCard>k__BackingField")) == item,
                  try m.pointer(item+p.offset("card_collection_item","<Card>k__BackingField")) == card else { throw ReadFailure("changing","Arena is replacing a displayed card.") }
            try append(r.cardID(card),1,"pack",q)
        }
        try require(rows.count == views.count, "Arena's pack is outside the current viewport.")
        return try result()
    }
    func deck(_ nav: UInt64) throws -> [String: Any] {
        let widget=try r.field(nav,"editor","_deckbuilder","widget")
        let pool=try r.field(widget,"widget","_poolCardHolder","pool_holder")
        let pages=try r.listObjects(m.pointer(pool+p.offset("pool_holder","_pages")),maximum:12)
        for page in pages {
            try r.check(page,"pool_page")
            let views=try r.listObjects(m.pointer(page+p.offset("pool_page","CardViews")),maximum:40)
            for view in views {
                try r.check(view,"pool_card")
                if try !active(native(view)) { continue }
                let card=try m.pointer(view+p.offset("meta_card_view","_cardData"),nullable:true)
                if card == 0 { continue }
                if try r.flag(view,"meta_card_view","<IsDragging>k__BackingField") { throw ReadFailure("changing","A card is being moved.") }
                let q=try cardRect(view)
                guard try m.pointer(view+p.offset("meta_card_view","_cardData")) == card else { throw ReadFailure("changing","Arena is replacing a pool card.") }
                let area=try uiRect(native(m.pointer(pool+p.offset("pool_holder","_rectTransform"))))
                try append(r.cardID(card),1,"pool",q,clip:area)
            }
        }
        let list=try r.field(widget,"widget","_deckListView","list_view")
        let holder=try r.field(list,"list_view","MainDeckCardHolder","list_holder")
        if try active(native(list)) {
            let area=try clip(m.pointer(holder+p.offset("meta_holder","ScrollRect")))
            for view in try r.listObjects(m.pointer(holder+p.offset("list_holder","_tiles")),maximum:300) {
                try r.check(view,"list_row")
                if try !active(native(view)) { continue }
                let count=try integer(m.read(view+p.offset("list_row","<Quantity>k__BackingField"),4),0,Int32.self)
                if count <= 0 { continue }
                let card=try m.pointer(view+p.offset("meta_card_view","_cardData"))
                let q=try uiRect(transform(native(view)))
                guard try m.pointer(view+p.offset("meta_card_view","_cardData")) == card,
                      try integer(m.read(view+p.offset("list_row","<Quantity>k__BackingField"),4),0,Int32.self) == count else { throw ReadFailure("changing","Arena is updating a deck row.") }
                try append(r.cardID(card),UInt64(count),"deck",q,clip:area)
            }
        }
        let columns=try r.field(widget,"widget","_deckColumnView","column_view")
        if try active(native(columns)) {
            let manager=try r.field(columns,"column_view","MainHolder","draft_column_holder")
            let area=try clip(m.pointer(manager+p.offset("column_manager","scrollRect")))
            for column in try r.listObjects(m.pointer(manager+p.offset("column_manager","_allColumns")),maximum:16) {
                try r.check(column,"column_holder")
                var cards: [(UInt64,UInt64,ArenaRect)]=[]
                for view in try r.listObjects(m.pointer(column+p.offset("column_holder","_cardViews")),maximum:300) {
                    try r.check(view,"column_card")
                    if try !active(native(view)) { continue }
                    let count=try integer(m.read(view+p.offset("column_card","_quantity"),4),0,Int32.self)
                    if count <= 0 { continue }
                    guard try !r.flag(view,"meta_card_view","<IsDragging>k__BackingField") else { throw ReadFailure("changing","A card is being moved.") }
                    let card=try m.pointer(view+p.offset("meta_card_view","_cardData"))
                    let q=try cardRect(view)
                    guard try m.pointer(view+p.offset("meta_card_view","_cardData")) == card,
                          try integer(m.read(view+p.offset("column_card","_quantity"),4),0,Int32.self) == count else { throw ReadFailure("changing","Arena is updating a deck card.") }
                    cards.append((try r.cardID(card),UInt64(count),q))
                }
                cards.sort { $0.2.top > $1.2.top }
                for (index,card) in cards.enumerated() {
                    let (id,count,q)=card
                    // Only the next card occludes this entry. The front card,
                    // including a single-card column, keeps its full collider.
                    let height=index+1 < cards.count ? min(q.h,q.top-cards[index+1].2.top) : q.h
                    guard height > 0.5 else { throw ReadFailure("changing","Arena is arranging the deck columns.") }
                    try append(id,count,"column",ArenaRect(x:q.x,y:q.top-height,w:q.w,h:height),clip:area)
                }
            }
        }
        return try result()
    }
}
