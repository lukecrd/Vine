package it.evoluzionetech.vine.core

/**
 * Minimal HTML built from the selectors in core.js (no real captured HTML in the repo).
 * The tile carries a decoy data-asin on itself: like querySelector, the parser must read descendants only.
 */
val A = Item("B013LEIETU", "Prodotto A", "https://www.amazon.it/dp/B013LEIETU")
val B = Item("B012345678", "Prodotto B", "https://www.amazon.it/dp/B012345678")

fun item(n: Int) = "B%09d".format(n).let { Item(it, "Prodotto $n", "https://www.amazon.it/dp/$it") }

fun page(
    items: List<Item> = listOf(A),
    count: String = "Visualizzazione di 1–${items.size} di ${items.size} risultati",
    selected: Boolean = true,
    blocked: Boolean = false,
    badLink: Boolean = false,
): String = """
<!doctype html><html lang="it"><body>
${if (blocked) "<form action=\"/ap/signin\"><input type=\"password\" name=\"password\"></form>" else ""}
<div id="vvp-items-button-container">
  <span id="vvp-items-button--recommended" class="a-button"><span class="a-button-inner"><a aria-checked="$selected" role="radio">Consigliati per te</a></span></span>
</div>
<div id="vvp-items-grid-container">
  <p>$count</p>
  <div id="vvp-items-grid">
${items.joinToString("\n") { i -> """
    <div class="vvp-item-tile" data-asin="B099999999">
      <div class="vvp-item-tile-content">
        <img src="https://m.media-amazon.com/x.jpg" alt="">
        <div class="vvp-item-product-title-container">
          <a class="a-link-normal" href="/dp/${if (badLink) "B099999999" else i.id}?ref=vine"><span class="a-truncate-full">  ${i.title}  </span><span class="a-truncate-cut">${i.title.take(5)}…</span></a>
        </div>
        <span class="a-button vvp-details-btn"><input class="a-button-input" type="submit" data-asin="${i.id}" data-recommendation-id="r"></span>
      </div>
    </div>""" }}
  </div>
</div>
</body></html>
"""
