# Vine — nuovi prodotti

**Pronto da installare; monitoraggio non attivo.** Chrome, estensione locale senza dipendenze. Non richiede credenziali. Non effettua richieste di prodotti, acquisti o ordini.

## Attivazione

In Chrome apri `chrome://extensions`, abilita **Modalità sviluppatore**, scegli **Carica estensione non pacchettizzata** e seleziona questa cartella `vine-monitor`. Poi ricarica la scheda [Vine](https://www.amazon.it/vine/vine-items?queue=potluck), apri **Vine — nuovi prodotti** dal menu Estensioni e premi **Avvia / Riprendi**. Il primo controllo salva la baseline senza avvisi. L'attivazione è confermata soltanto quando compare **Attivo — baseline silenziosa salvata** e una data di ultimo controllo.

## Uso

- Frequenza richiesta: 60 secondi tramite allarme del browser. Computer acceso e scheda Vine aperta; sospensione del PC e risparmio energetico possono ritardare i controlli. Non è una garanzia temporale rigida.
- Ogni ASIN nuovo genera un avviso di sistema e una voce persistente nel registro con link al prodotto. Chrome/Windows possono silenziare gli avvisi: il registro resta consultabile dall'estensione. Nessun filtro per categoria o prezzo.
- **Ferma** sospende le letture e conserva i già visti. **Riprendi** mantiene la memoria. Al riavvio di Chrome occorre Avvia. Alla chiusura della scheda monitorata il controllo si ferma.
- Errori, CAPTCHA, accesso scaduto e pagine non riconosciute sospendono i controlli senza aggiornare i già visti. Risolvi manualmente nella scheda e premi Riprendi. Nessun aggiramento o tentativo ripetuto automatico.
- La memoria è locale al profilo Chrome; disinstallare l'estensione la elimina. Non usare la stessa installazione per account Amazon diversi: la memoria degli ASIN è condivisa nell'installazione.

## Verifiche e limiti reali

Il 19/09/2026 è stata letta la pagina autenticata: coda potluck selezionata, un risultato. Verificati sul DOM reale: `#vvp-items-grid-container`, `.vvp-item-tile`, attributo `data-asin`, link `/dp/ASIN`, titolo `.a-truncate-full`, selezione `#vvp-items-button--recommended [aria-checked="true"]` e conteggio italiano. I prodotti suggeriti fuori dalla griglia vengono esclusi.

La scansione usa GET della pagina canonica con la sessione del browser e interpreta l'HTML senza eseguire script. La risposta GET automatica e l'installazione non sono state provate dal vivo. Se Amazon restituisce HTML diverso, il monitor si sospende.

Paginazione `page=N` implementata con controlli su intervalli e conteggi, ma non verificata dal vivo perché la coda aveva una sola pagina. Limite difensivo: 100 pagine e 40 secondi complessivi. Catalogo che cambia tra pagine: sospensione, nessun avviso parziale. La struttura della coda vuota non era osservabile: un conteggio esplicito 0–0 di 0 è supportato; altre presentazioni vuote sospendono finché non vengono verificate. Un prodotto apparso e scomparso tra due controlli può non essere rilevato.

ASIN uguale significa prodotto già visto anche se rimosso, ripubblicato o rinominato. Nessuna scadenza automatica dei già visti. Eventi e già visti vengono salvati insieme prima delle notifiche: un arresto improvviso può impedire un avviso di sistema, ma l'evento salvato resta nel registro e non viene duplicato. Raggiungere la quota di memoria arresta i controlli: nessuna cancellazione silenziosa dei vecchi ASIN.

Test locali senza dipendenze: `node --test test.cjs`. Verificano il contratto del parser con simulazioni DOM, URL ammessi, baseline, persistenza, deduplicazione, allarme e sospensione. Non sostituiscono un test dell'estensione installata o delle notifiche Windows.

## File e permessi

`core.js`: parser e memoria; `reader.js`: sole GET; `background.js`: allarmi, stato e notifiche; `popup.*`: controlli e registro. Permessi: storage locale, allarmi, notifiche, accesso al percorso Amazon Vine. Nessuna telemetria, libreria esterna, scheduler di sistema o servizio remoto aggiuntivo.
