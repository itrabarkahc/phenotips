/**
 * Specific version of GeneLegend for rejected genes. It is not displayed but used
 * because of the way gene update machinery works (to update gene IDs for gene known only by their symbols)
 *
 * @class RejecetedGeneLegend
 * @constructor
 */
 define(["pedigree/view/geneLegend"], function(GeneLegend){
    var RejectedGeneLegend = Class.create( GeneLegend, {

        initialize: function($super) {
            $super('Rejected Genes', 'genes',
                   "rejected",
                   [], // these are never displayed in a legend so don't need colours
                   "getRejectedGenes",
                   "setRejectedGenes", true); // operation
        },

        addCase: function($super, id, symbol, nodeID) {
            $super(id, symbol, nodeID, true);
        }
    });
    return RejectedGeneLegend;
});
