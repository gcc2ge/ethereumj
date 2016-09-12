package org.ethereum.core;

public enum ImportResult {
    IMPORTED_BEST,//连接之后位于主区块上
    IMPORTED_NOT_BEST,//连接之后位于支区块上
    EXIST,//已存在区块链上，丢弃之
    NO_PARENT,//区块的父区块不存在，丢弃之
    INVALID_BLOCK,//区块未通过验证，丢弃之
    CONSENSUS_BREAK;//

    public boolean isSuccessful() {
        return equals(IMPORTED_BEST) || equals(IMPORTED_NOT_BEST);
    }
}
