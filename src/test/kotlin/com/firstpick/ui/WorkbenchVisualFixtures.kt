package com.firstpick.ui

import com.firstpick.draft.DraftHistoryEntry
import com.firstpick.model.*
import com.firstpick.overlay.*
import com.firstpick.stats.*

/** The approved HTML prototype's example data, isolated from production and the player's database. */
internal object WorkbenchVisualFixtures {
    private val names = listOf("Dark Matter Manipulator", "Emergency Phytomedic", "Bushy Bodyguard", "Arcane Amphisbaena", "Compel Brutality", "Konstrari Improviser", "Bark-Knuckle Boxer", "Fell", "Ferocity of the Hunt", "Konstrari Charm", "Darklight Phoenix", "Jiang Yanggu", "Hungering Puppet", "Vineslasher Adept", "Heir of Konstrari", "Ruinous Arrival", "Cragbound Protector", "Colossal Rootwurm")
    private val costs = listOf("{B}", "{G/W}", "{G}", "{1}{G}", "{1}{G}", "{1}{R/G}", "{1}{G}", "{1}{B}", "{1}{G}", "{B}{R}{G}", "{2}{B}", "{2}{G}", "{2}{G}", "{3}{G}", "{3}{G}", "{4}{R}", "{4}{G}", "{4}{G}{G}")
    private val quantities = listOf(1,1,1,2,1,2,1,1,1,1,1,1,2,2,2,1,1,1)
    val spells = names.mapIndexed { i, n -> DeckSpellUi(n, when(i) { in 0..2 -> 1; in 3..8 -> 2; in 9..12 -> 3; 13,14 -> 4; 15,16 -> 5; else -> 6 }, when(i){0,7,10->"B";9->"BRG";15->"R";else->"G"}, if(i==0).572 else if(i==1).54 else null, count=quantities[i], typeLabel=if(i in listOf(4,7,9,15)) "Instant" else "Creature", roles=if(i in listOf(4,7,9))listOf("Removal") else emptyList(),manaCost=costs[i]) }
    val lands = listOf(Triple("Forest","G",10),Triple("Mountain","R",2),Triple("Swamp","B",2),Triple("Duals & fixing","",3)).map{(n,c,q)->DeckSpellUi(n,0,c,null,count=q,isLand=true,isBasicLand=n!="Duals & fixing")}
    val builds = listOf("BRG" to 46,"BG" to 46,"WBG" to 40).map{(c,p)->DeckOptionUi(c,if(c=="BRG")"BG" else c.takeLast(2),tier="B",type="Midrange",outlook="Mana needs attention",power=p,identityConfidence="High",identityReasons=listOf("Three-color mana asks for reliable fixing."),powerConfidence="Medium",powerReasons=listOf("Example metrics for visual comparison only."),creatures=18,removal=3,landLine="17 lands",spells=spells,lands=lands,isThreeColor=c.length==3,theme=if(c=="BRG")"Midrange" else null)}
    private val ranking=listOf(0,7,3,1,5,2,4,6,9,10,11,12,8,13)
    private val scores=listOf(91,84,80,75,71,66,61,57,54,51,48,45,41,37)
    val state=DraftUiState(phase=DraftPhase.DRAFTING,setReferences=listOf(com.firstpick.guide.SetReference("FRA","Reality Fracture",emptyList()),com.firstpick.guide.SetReference("DSK","Duskmourn",emptyList()),com.firstpick.guide.SetReference("BLB","Bloomburrow",emptyList())),setCode="FRA",format=DraftFormat.PREMIER,pack=2,pick=3,poolSize=15,poolCreatures=11,poolNonCreatures=4,laneColors=listOf('B','G'),deckOptions=builds,draftPool=spells+listOf(DeckSpellUi("Fblthp, Knows the Way",3,"U",null),DeckSpellUi("Refute Destiny",2,"W",null),DeckSpellUi("Eddymurk Crab",6,"U",null)),manaCurve=listOf(4,8,3,0,0,0).mapIndexed{i,n->CurveBar(if(i==5)"6+" else "${i+1}",n)},packCards=ranking.mapIndexed{i,id->val c=spells[id];PackCardUi(id,id,i+1,c.name,c.color,"common",c.gihWr,null,null,value=scores[i].toDouble(),reasons=listOf(listOf("Efficient threat in your main colors","Adds needed removal","On-color creature and value","Early creature, flexible cost","Helps support a future splash","Early creature slot already covered","Compare with stronger interaction","Less impact for this example pool","Powerful effect, harder mana requirements","On-color threat, less useful than interaction","Another creature at three mana","Competes with stronger creatures","Slower contribution to the current pool","Expensive creature, curve is already covered")[i]),manaCost=c.manaCost)})
    private val wins=listOf(3,5,7,2,7,6)
    private val losses=listOf(3,3,1,3,2,3)
    private val sets=listOf("FRA","DSK","FRA","BLB","FRA","FRA")
    val courses=wins.mapIndexed{i,w->CourseRecord("visual-$i","2026-11-0${i+1}T12:00:00Z", "${if(i in listOf(1,3))"QuickDraft" else "PremierDraft"}_${sets[i]}",w,losses[i],complete=true,historyId="visual-$i")}
    val history=courses.map{c->DraftHistoryEntry(c.id,c.at,DraftState(phase=DraftPhase.COMPLETE,setCode=sets[courses.indexOf(c)],eventName=c.event,courseId=c.id,pool=(0..17).toList(),seen=mapOf((1 to 1) to ranking)))}
    val stats=PlayerStats(courses=courses,matches=courses.flatMap{c->List(c.wins!!+c.losses!!){i->MatchRecord("${c.id}-$i",c.at,c.event,c.id,if(i<c.wins)"Win" else "Loss",onPlay=i%2==0)}})
    val review=state.copy(phase=DraftPhase.COMPLETE,savedDeck=DeckSnapshot((spells+lands).map{DeckCardCount(it.name,it.count,it.isBasicLand)},40))
}
