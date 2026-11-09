package android.view
class MenuItem(val itemId: Int) { var isVisible = true }
class Menu { val items = mutableMapOf<Int, MenuItem>(); fun findItem(id: Int): MenuItem? = items[id] }
class MenuInflater { fun inflate(res: Int, m: Menu) {} }
