package com.rag2agent.bootstrap.dto;

import com.rag2agent.bootstrap.dto.TableDtos.BoundingBoxView;
import com.rag2agent.bootstrap.service.DocumentStructureRepository.AssetRecord;
import jakarta.validation.constraints.NotBlank;

public final class AssetDtos {

    private AssetDtos() {}

    public record AssetView(
            String assetId,
            String type,
            String title,
            String contentType,
            String visionText,
            Integer pageNo,
            BoundingBoxView boundingBox,
            String url,
            int expiresInSeconds) {

        public static AssetView from(AssetRecord asset, String url, int expiresInSeconds) {
            return new AssetView(
                    asset.assetId(),
                    asset.assetType(),
                    asset.title(),
                    asset.contentType(),
                    asset.visionText(),
                    asset.pageNo(),
                    asset.boundingBox() == null
                            ? null
                            : new BoundingBoxView(
                                    asset.boundingBox().left(),
                                    asset.boundingBox().top(),
                                    asset.boundingBox().right(),
                                    asset.boundingBox().bottom(),
                                    asset.boundingBox().coordinateOrigin()),
                    url,
                    expiresInSeconds);
        }
    }

    public record DescribeAssetRequest(
            @NotBlank(message = "prompt 不能为空") String prompt) {}

    public record DescribeAssetResponse(
            String assetId, String answer, String provider, String model) {}
}
